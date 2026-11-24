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

    /// [text] drawn: every run carries its own font and colour; `draw` says how each mention looks. A resolved
    /// mention is its name here (the pills are [pieces]); with [todoLinks] each to-do's box is a `yoin-todo:` link.
    static func attributed(_ text: String, style: NoteMarkupStyle, todoLinks: Bool = false,
                           draw: (YoinNoteMention) -> NoteMentionDraw) -> AttributedString {
        var out = AttributedString()
        for piece in pieces(text, style: style, todoLinks: todoLinks, draw: draw) {
            switch piece {
            case .text(let a): out += a
            case .pill(let pill): out += pill.plainRun(style)
            }
        }
        return out
    }

    /// [text] as runs of words and the pills its resolved mentions become (Android buildNoteMarkup).
    static func pieces(_ text: String, style: NoteMarkupStyle, todoLinks: Bool = false,
                       draw: (YoinNoteMention) -> NoteMentionDraw) -> [NoteMarkupPiece] {
        var out: [NoteMarkupPiece] = []
        var pending = AttributedString()
        func flush() {
            if !pending.characters.isEmpty { out.append(.text(pending)) }
            pending = AttributedString()
        }
        var ordinal = 0
        for (index, line) in lines(text).enumerated() {
            if index > 0 { pending += plain("\n", style) }
            let kind = line.kind
            var size = style.size
            var lineWeight = style.weight
            if kind == .heading {
                // Android: Bold, 1.15 em for #, 1.05 em for ## / ###.
                size = style.size * (line.level == 1 ? 1.15 : 1.05)
                lineWeight = .bold
            } else if kind == .bullet {
                pending += plain(bullet, style)
            } else if kind == .checklist {
                var glyph = plain(line.checked ? done : todo, style)
                glyph.foregroundColor = style.glyphColor
                // A tap on the box ticks it (the host saves the note).
                if todoLinks { glyph.link = todoURL(line: index) }
                pending += glyph
            }
            for run in line.runs {
                let weight = run.bold ? .bold : lineWeight
                var piece = AttributedString(run.text)
                if let mention = run.mention {
                    let drawn = draw(mention)
                    if drawn != .plain {
                        flush()
                        let link: URL? = if case .linked(let url) = drawn { url } else { nil }
                        out.append(.pill(NoteMentionPill(ordinal: ordinal, mention: mention, size: size, italic: run.italic,
                                                          link: link)))
                        ordinal += 1
                        continue
                    }
                }
                if kind == .checklist && line.checked { piece.strikethroughStyle = Text.LineStyle.single }
                piece.font = font(size: size, weight: weight, italic: run.italic, serif: style.serif)
                pending += piece
            }
        }
        flush()
        return out
    }

    /// The `yoin-todo:` link on line [line]'s box.
    static func todoURL(line: Int) -> URL { URL(string: "yoin-todo:\(line)")! }

    /// The to-do line a `yoin-todo:` link names.
    nonisolated static func todoLine(_ url: URL) -> Int? {
        guard url.scheme?.lowercased() == "yoin-todo" else { return nil }
        return Int(url.absoluteString.dropFirst("yoin-todo:".count))
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

/// One piece of drawn note text: words, or a resolved mention's pill.
enum NoteMarkupPiece {
    case text(AttributedString)
    case pill(NoteMentionPill)
}

/**
 * A resolved mention as Android 0.6.4 draws it (owner 2026-11-24, "looks like plain bold"): a small cover as
 * tall as the words, then the name, both on a faint pill of the mention colour, no underline. The pill is one
 * unbreakable piece: it moves whole to the next line, and a name longer than a line ends in an ellipsis inside
 * it, the cover kept. Here it is its name's characters glued together (no break opportunity inside), with room
 * kerned in for the cover; [MentionPillRenderer] draws the pill and the cover behind them.
 */
struct NoteMentionPill: Equatable {
    var ordinal: Int
    var mention: YoinNoteMention
    /// The line's type size (a heading's is larger), before Dynamic Type.
    var size: CGFloat
    var italic: Bool
    /// The mention's `yoin:` link; nil when it is drawn but takes no tap.
    var link: URL?

    /// Android MentionPad* / MentionThumbEm / MentionPillHeightEm, in ems of the name's size.
    enum Metrics {
        static let padStart: CGFloat = 0.22
        static let thumb: CGFloat = 1
        static let gap: CGFloat = 0.3
        static let padEnd: CGFloat = 0.45
        static let height: CGFloat = 1.3
        static let fill: Double = 0.12
        static let pressedFill: Double = 0.26
        static let thumbFill: Double = 0.16
        /// Before the name: the lead, the cover and the gap.
        static var lead: CGFloat { padStart + thumb + gap }
        static var chrome: CGFloat { lead + padEnd }
    }

    /// The name as plain words (accessibility, the tests' reading): the mention colour, SemiBold, its link.
    @MainActor func plainRun(_ style: NoteMarkupStyle) -> AttributedString {
        var a = AttributedString(mention.title)
        a.font = NoteMarkupRender.font(size: size, weight: NoteMarkupRender.mentionWeight, italic: italic, serif: style.serif)
        a.foregroundColor = style.mentionColor
        a.link = link
        return a
    }

    /// The pill's characters for a line [lineWidth] wide (nil: unknown yet, nothing cut): a no-break space
    /// kerned to the lead, the name glued, a no-break space kerned to the tail. [scaled]: the size after
    /// Dynamic Type, [measure] a string's width at it.
    @MainActor func run(scaled: CGFloat, lineWidth: CGFloat?, measure: (String) -> CGFloat, style: NoteMarkupStyle) -> AttributedString {
        let name = Self.fit(mention.title, scaled: scaled, lineWidth: lineWidth, measure: measure)
        let space = measure("\u{00A0}")
        var lead = AttributedString("\u{00A0}")
        lead.kern = max(0, Metrics.lead * scaled - space)
        let body = AttributedString(Self.glued(name))
        var tail = AttributedString("\u{00A0}")
        tail.kern = max(0, Metrics.padEnd * scaled - space)
        var out = lead + body + tail
        out.font = NoteMarkupRender.font(size: size, weight: NoteMarkupRender.mentionWeight, italic: false, serif: style.serif)
        out.foregroundColor = style.mentionColor
        out.link = link
        return out
    }

    /// [name] with no break opportunity inside: spaces become no-break spaces and a word joiner (U+2060) sits
    /// between every two characters, so CJK names and hyphens hold too (tablet QA: "林 / 间" was cut).
    static func glued(_ name: String) -> String {
        var out = ""
        for (i, c) in name.enumerated() {
            if i > 0 { out.append("\u{2060}") }
            out.append(c == " " ? "\u{00A0}" : c)
        }
        return out
    }

    /// [title] cut with an ellipsis so the pill fits [lineWidth] (Android mentionPillGeometry); whole when it fits
    /// or the line is unknown. At least one character stays.
    static func fit(_ title: String, scaled: CGFloat, lineWidth: CGFloat?, measure: (String) -> CGFloat) -> String {
        guard let lineWidth, lineWidth.isFinite, lineWidth > 0 else { return title }
        // A point of slack: kerning and rounding never push the pill past the line.
        let room = max(lineWidth - Metrics.chrome * scaled - 2, scaled)
        if measure(title) <= room { return title }
        let chars = Array(title)
        var lo = 1, hi = max(1, chars.count - 1)
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            if measure(String(chars.prefix(mid)) + "…") <= room { lo = mid } else { hi = mid - 1 }
        }
        let kept = String(chars.prefix(lo)).trimmingCharacters(in: .whitespaces)
        return (kept.isEmpty ? String(chars.prefix(1)) : kept) + "…"
    }
}

/// Marks a pill's characters for the renderer.
struct MentionPillAttribute: TextAttribute {
    var ordinal: Int
    /// The name's size after Dynamic Type.
    var scaled: CGFloat
    var kind: MentionKindToken
    var tint: Color
}

/// A mention's kind as a plain Swift value (Kotlin enums aren't Hashable value types here).
enum MentionKindToken: Hashable, Sendable {
    case track, album, artist

    init(_ kind: MentionKind) {
        self = kind == .artist ? .artist : (kind == .album ? .album : .track)
    }

    /// The kind's Yoin Symbol (Android MentionKind.symbol).
    var symbol: String {
        switch self {
        case .track: "yoin.music_note"
        case .album: "yoin.album"
        case .artist: "yoin.artist"
        }
    }
}

/// Where each pill was drawn (the renderer writes it, the long press reads it), in the text's own space.
final class MentionHitBox: @unchecked Sendable {
    private let lock = NSLock()
    private var rects: [(ordinal: Int, rect: CGRect)] = []

    func set(_ next: [(ordinal: Int, rect: CGRect)]) {
        lock.lock(); rects = next; lock.unlock()
    }

    /// The first pill's rect.
    var first: CGRect? {
        lock.lock(); defer { lock.unlock() }
        return rects.first?.rect
    }

    /// The pill under [point] (within a few points), and its first rect.
    func hit(_ point: CGPoint, slop: CGFloat = 6) -> (ordinal: Int, rect: CGRect)? {
        lock.lock(); defer { lock.unlock() }
        return rects.first { $0.rect.insetBy(dx: -slop, dy: -slop).contains(point) }
    }
}

/// Draws each pill (its tint, its cover or its kind's symbol) behind its characters, then the text as laid out.
struct MentionPillRenderer: TextRenderer {
    /// Covers by pill ordinal; a missing one draws the kind's symbol.
    var covers: [Int: Image]
    var pressed: Int?
    var hits: MentionHitBox

    func draw(layout: Text.Layout, in ctx: inout GraphicsContext) {
        var drawn: [(ordinal: Int, rect: CGRect)] = []
        for line in layout {
            // One rect per pill on this line (its runs side by side).
            var pills: [(attr: MentionPillAttribute, rect: CGRect)] = []
            for run in line {
                guard let attr = run[MentionPillAttribute.self] else { continue }
                let r = run.typographicBounds.rect
                if let i = pills.lastIndex(where: { $0.attr.ordinal == attr.ordinal }) {
                    pills[i].rect = pills[i].rect.union(r)
                } else {
                    pills.append((attr, r))
                }
            }
            for (attr, r) in pills {
                let em = attr.scaled
                let height = NoteMentionPill.Metrics.height * em
                let pill = CGRect(x: r.minX, y: r.midY - height / 2, width: r.width, height: height)
                let fill = attr.ordinal == pressed ? NoteMentionPill.Metrics.pressedFill : NoteMentionPill.Metrics.fill
                ctx.fill(Path(roundedRect: pill, cornerRadius: height / 2), with: .color(attr.tint.opacity(fill)))
                let side = NoteMentionPill.Metrics.thumb * em
                let thumb = CGRect(x: pill.minX + NoteMentionPill.Metrics.padStart * em, y: pill.midY - side / 2,
                                   width: side, height: side)
                let shape = attr.kind == .artist ? Path(ellipseIn: thumb) : Path(roundedRect: thumb, cornerRadius: side * 0.24)
                if let cover = covers[attr.ordinal] {
                    var clip = ctx
                    clip.clip(to: shape)
                    clip.draw(cover, in: thumb)
                } else {
                    ctx.fill(shape, with: .color(attr.tint.opacity(NoteMentionPill.Metrics.thumbFill)))
                    var symbol = ctx.resolve(Image(attr.kind.symbol))
                    symbol.shading = .color(attr.tint)
                    ctx.draw(symbol, in: thumb.insetBy(dx: side * 0.14, dy: side * 0.14))
                }
                drawn.append((attr.ordinal, pill))
            }
            ctx.draw(line)
        }
        hits.set(drawn)
    }
}

/// A note, song note or review drawn from its stored text (Android NoteMarkupText). Resolved mentions are
/// pills that open what they name on a tap (unless [links] is off) and peek at it on a long press; a tap
/// elsewhere goes to the host. With [onToggleChecklist], each to-do's box ticks it.
struct NoteMarkupText: View {
    var text: String
    var size: CGFloat
    var weight: Font.Weight = .regular
    var mentionColor: Color
    var glyphColor: Color
    /// False: mentions are drawn but take no tap or long press (the host takes every touch).
    var links = true
    var serif = false
    /// A tap on a to-do's box (song notes and album notes; not reviews, as on Android).
    var onToggleChecklist: ((Int) -> Void)? = nil

    @State private var lineWidth: CGFloat?
    @State private var hits = MentionHitBox()
    @State private var pressed: Int?

    var body: some View {
        let mentions = NoteMentions.shared
        let style = NoteMarkupStyle(size: size, weight: weight, mentionColor: mentionColor, glyphColor: glyphColor,
                                    serif: serif)
        let toggles = links && onToggleChecklist != nil
        let pieces = NoteMarkupRender.pieces(text, style: style, todoLinks: toggles) { mentions.draw($0, links: links) }
        let pills = pieces.compactMap { if case .pill(let p) = $0 { p } else { nil } }
        Group {
            if pills.isEmpty {
                Text(Self.join(pieces, style: style))
            } else {
                pillText(pieces, pills: pills, style: style)
            }
        }
        .tint(mentionColor)
        .environment(\.openURL, OpenURLAction { url in
            if let line = NoteMarkupRender.todoLine(url) {
                onToggleChecklist?(line)
                return .handled
            }
            return mentions.handle(url, in: text)
        })
    }

    private static func join(_ pieces: [NoteMarkupPiece], style: NoteMarkupStyle) -> AttributedString {
        pieces.reduce(into: AttributedString()) { out, piece in
            if case .text(let a) = piece { out += a } else if case .pill(let p) = piece { out += p.plainRun(style) }
        }
    }

    @ViewBuilder
    private func pillText(_ pieces: [NoteMarkupPiece], pills: [NoteMentionPill], style: NoteMarkupStyle) -> some View {
        let artwork = NoteMentionArtwork.shared
        let refs = pills.map { artwork.cover($0.mention) }
        let covers = Self.covers(pills, refs)
        let text = Self.compose(pieces, style: style, lineWidth: lineWidth)
        let reader = ProposedWidthReader { width in
            if lineWidth.map({ abs($0 - width) > 0.5 }) ?? true { lineWidth = width }
        }
        reader {
            text.textRenderer(MentionPillRenderer(covers: covers, pressed: pressed, hits: hits))
        }
        .gesture(MentionLongPress(enabled: links, hits: hits) { ordinal, rect in
            pressed = ordinal
            guard let ordinal, let pill = pills.first(where: { $0.ordinal == ordinal }), let rect else {
                MentionPeekPresenter.shared.hide()
                return
            }
            let ref = refs[pills.firstIndex(of: pill) ?? 0]
            let cover = ref.flatMap { CoverArtwork.shared.image($0) } ?? artwork.mockImage(pill.mention)
            MentionPeekPresenter.shared.show(pill.mention, cover: cover, from: rect)
        })
        .accessibilityRepresentation { Text(Self.join(pieces, style: style)) }
        #if DEBUG
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
            Self.peekDemo(frame, hits: hits, pills: pills)
        }
        #endif
        .task(id: refs.compactMap { $0?.key }) {
            for ref in refs.compactMap({ $0 }) { await CoverArtwork.shared.load(ref) }
        }
    }

    #if DEBUG
    private static var peekDemoShown = false

    /// `-noteMentionsDemo peek`: the first pill drawn is held as if long-pressed (screenshots).
    private static func peekDemo(_ frame: CGRect, hits: MentionHitBox, pills: [NoteMentionPill]) {
        guard NoteMentionsDemo.mode == "peek", !peekDemoShown, let pill = pills.first else { return }
        peekDemoShown = true
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2))
            guard let hit = hits.first else { return }
            MentionPeekPresenter.shared.show(pill.mention, cover: NoteMentionArtwork.shared.mockImage(pill.mention),
                                             from: hit.offsetBy(dx: frame.minX, dy: frame.minY))
        }
    }
    #endif

    /// Each pill's loaded cover (tracked: one landing later redraws), by ordinal.
    private static func covers(_ pills: [NoteMentionPill], _ refs: [CoverRef?]) -> [Int: Image] {
        var out: [Int: Image] = [:]
        for (pill, ref) in zip(pills, refs) {
            if let ref, let image = CoverArtwork.shared.image(ref) {
                out[pill.ordinal] = Image(uiImage: image)
            } else if let mock = NoteMentionArtwork.shared.mockImage(pill.mention) {
                out[pill.ordinal] = Image(uiImage: mock)
            }
        }
        return out
    }

    /// The words and pills as one Text: each pill's characters carry [MentionPillAttribute] for the renderer.
    @MainActor
    static func compose(_ pieces: [NoteMarkupPiece], style: NoteMarkupStyle, lineWidth: CGFloat?) -> Text {
        var parts = LocalizedStringKey.StringInterpolation(literalCapacity: 0, interpolationCount: pieces.count)
        for piece in pieces {
            switch piece {
            case .text(let a):
                parts.appendInterpolation(Text(a))
            case .pill(let pill):
                let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(for: pill.size)
                let font = YoinFont.staticUIFont(size: scaled, weight: NoteMarkupRender.mentionWeight)
                let run = pill.run(scaled: scaled, lineWidth: lineWidth, measure: { ($0 as NSString).size(withAttributes: [.font: font]).width },
                                   style: style)
                parts.appendInterpolation(Text(run).customAttribute(
                    MentionPillAttribute(ordinal: pill.ordinal, scaled: scaled, kind: MentionKindToken(pill.mention.kind),
                                         tint: style.mentionColor)))
            }
        }
        return Text(LocalizedStringKey(stringInterpolation: parts))
    }
}

/// Passes its child the width it is offered and reports that width (the line a pill must fit).
struct ProposedWidthReader: Layout {
    var report: (CGFloat) -> Void

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        subviews.first?.sizeThatFits(proposal) ?? .zero
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
        if let w = proposal.width, w.isFinite, w > 0 {
            let report = report
            DispatchQueue.main.async { report(w) }
        }
    }
}
