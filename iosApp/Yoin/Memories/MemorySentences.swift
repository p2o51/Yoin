import SwiftUI
import UIKit

/// Whole-sentence handling for Memories' excerpts (Android copy/MemorySentences): the card never cuts
/// a sentence and never shows an ellipsis.
enum MemorySentences {
    private static let fullStops: Set<Character> = ["。", "！", "？", "!", "?"]
    private static let closers: Set<Character> = ["。", "！", "？", "!", "?", ".", "”", "’", "」", "）", ")"]

    /// Splits on 。！？.!? A "." right before a digit (8.5) is no stop; closing quotes and brackets after
    /// a stop stay with its sentence.
    static func split(_ text: String) -> [String] {
        let chars = Array(text)
        var out: [String] = []
        var current = ""
        var i = 0
        func flush() {
            let t = current.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty { out.append(t) }
            current = ""
        }
        while i < chars.count {
            let c = chars[i]
            current.append(c)
            let next = i + 1 < chars.count ? chars[i + 1] : nil
            if fullStops.contains(c) || (c == "." && !(next?.isASCII == true && next?.isNumber == true)) {
                while i + 1 < chars.count, closers.contains(chars[i + 1]) {
                    i += 1
                    current.append(chars[i])
                }
                flush()
            }
            i += 1
        }
        flush()
        return out
    }

    /// Running text again: a space between sentences, none after CJK or full-width punctuation (a
    /// closing ” takes one unless what it closes is CJK).
    static func join(_ sentences: [String]) -> String {
        sentences.reduce("") { acc, s in
            guard let last = acc.last else { return s }
            var space = !cjk(last)
            if last == "”" { space = acc.dropLast().last.map { !cjk($0) } ?? true }
            return space ? acc + " " + s : acc + s
        }
    }

    private static func cjk(_ c: Character) -> Bool {
        c.unicodeScalars.contains { (0x3000...0x303F).contains($0.value) || (0xFF00...0xFFEF).contains($0.value)
            || (0x3400...0x9FFF).contains($0.value) }
    }

    /// A review's excerpt candidates, best first (Android MemoryExcerpt.candidates): whole sentences from
    /// the start of its first paragraph, the most first, down to the first sentence alone.
    static func reviewCandidates(_ review: String) -> [String] {
        let paragraph = review.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: true).first.map(String.init) ?? review
        let sentences = split(paragraph)
        return stride(from: sentences.count, through: 1, by: -1).map { join(Array(sentences.prefix($0))) }
    }
}

/// The card's excerpt slot (MemoryPage): its type by length, and whether a candidate fits in four
/// lines at a width — the first one that does is shown; none does: the attribution alone.
enum MemoryExcerptFit {
    static let maxLines = 4
    static let maxWidth: CGFloat = 324

    static func isShort(_ text: String) -> Bool { text.count < 60 }
    static func size(_ text: String) -> CGFloat { isShort(text) ? 22 : 17 }
    static func lineSpacing(_ text: String) -> CGFloat { isShort(text) ? 22 * 0.4 - 6 : 17 * 0.65 - 5 }
    static func weight(_ text: String) -> Font.Weight { isShort(text) ? .medium : .regular }

    /// The quoted text as the card sets it.
    static func quoted(_ text: String) -> String { String(localized: "“\(text)”") }

    /// Lines [text] takes at [width] in the card's face (Dynamic Type included).
    static func lines(_ text: String, width: CGFloat) -> Int {
        let name = isShort(text) ? "GoogleSansFlex-Medium" : "GoogleSansFlex-Regular"
        let base = UIFont(name: name, size: size(text)) ?? .systemFont(ofSize: size(text))
        let font = UIFontMetrics(forTextStyle: .body).scaledFont(for: base)
        let style = NSMutableParagraphStyle()
        style.lineSpacing = lineSpacing(text)
        style.alignment = .center
        let rect = (quoted(text) as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: font, .paragraphStyle: style], context: nil)
        let line = font.lineHeight + style.lineSpacing
        return max(1, Int(((rect.height + style.lineSpacing) / line).rounded()))
    }

    /// The first candidate that fits, or nil (the attribution alone).
    static func pick(_ candidates: [String], width: CGFloat) -> String? {
        candidates.first { lines($0, width: width) <= maxLines }
    }
}
