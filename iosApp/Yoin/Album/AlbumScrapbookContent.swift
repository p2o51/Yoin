import Foundation

/// Page 2's fixed rules and the pieces of a track's cluster, as Android's buildAlbumScrapbook builds
/// them (AlbumScrapbook.kt, D3 §5; parity B-F9/B-F10). Pure, so the same inputs always give the same
/// page and tests can pin it.
enum ScrapbookRules {
    /// Notes shown on a clipping before "+N notes".
    static let notesShown = 3
    /// About facts shown as tags under a card before the rest go to the sheet.
    static let tagsShown = 3
    /// Weighted characters (CJK counts 2) a question card shows on a phone.
    static let answerBudget = 130
    /// Answers show at most this many whole sentences on the card, never an ellipsis.
    static let answerSentences = 2
}

/// The excerpt a card shows and whether anything was left for the sheet.
struct ScrapExcerpt: Equatable {
    var text: String
    var truncated: Bool
}

/// A track's About on the paper: the newest question (or the listener's own entry), the About
/// paragraph's first sentence when there is no question, and the short facts as tags.
struct ScrapAboutPieces: Equatable {
    struct Ask: Equatable {
        /// The listener's words (the question as typed, or an own entry's heading).
        var question: String
        /// Gemini's title for it; nil for the listener's own entry.
        var title: String?
        var answer: String
    }

    struct Tag: Equatable {
        var label: String
        var value: String
    }

    var ask: Ask?
    var moreAsks: Int
    var aboutLine: String?
    var tags: [Tag]
    var hiddenTags: Int
    /// Anything on the paper at all (a question or a fact).
    var touches: Bool

    static let none = ScrapAboutPieces(ask: nil, moreAsks: 0, aboutLine: nil, tags: [], hiddenTags: 0, touches: false)

    /// [entries] are the song's About rows as SongAboutStore lists them (newest first).
    static func of(_ entries: [SongAboutStore.Entry]) -> ScrapAboutPieces {
        let live = entries.filter { !$0.isBlank }
        let asks = live.filter { $0.kind != SongAboutStore.Kind.canonical }
        let facts = SongAboutStore.Canon.order.compactMap { key in
            live.first { $0.kind == SongAboutStore.Kind.canonical && $0.entryKey == key }
        }
        let ask = asks.first.map { e in
            e.kind == SongAboutStore.Kind.own
                ? Ask(question: e.titleText ?? "", title: nil, answer: e.answerText)
                : Ask(question: e.promptText ?? e.titleText ?? "", title: e.titleText.flatMap { $0.isEmpty ? nil : $0 },
                      answer: e.answerText)
        }
        let paragraph = facts.first { $0.entryKey == SongAboutStore.Canon.review }?.answerText
        let aboutLine = asks.isEmpty
            ? paragraph.map { ScrapbookText.answerExcerpt($0, budget: ScrapbookRules.answerBudget, maxSentences: 1).text }
            : nil
        let allTags = facts.filter { $0.entryKey != SongAboutStore.Canon.review }
            .map { Tag(label: ScrapbookText.factLabel($0.entryKey), value: $0.answerText) }
        return ScrapAboutPieces(ask: ask, moreAsks: max(asks.count - 1, 0),
                                aboutLine: aboutLine?.isEmpty == false ? aboutLine : nil,
                                tags: Array(allTags.prefix(ScrapbookRules.tagsShown)),
                                hiddenTags: max(allTags.count - ScrapbookRules.tagsShown, 0),
                                touches: !asks.isEmpty || !facts.isEmpty)
    }

    /// The track gets a cluster of its own (not a contact-sheet ticket): a question, a fact or the
    /// About paragraph on it.
    var needsCluster: Bool { ask != nil || aboutLine != nil || !tags.isEmpty }
}

enum ScrapbookText {
    /// Android factLabel: the tag's label for a canonical key.
    static func factLabel(_ key: String) -> String {
        switch key {
        case SongAboutStore.Canon.creationTime: "Created"
        case SongAboutStore.Canon.creationLocation: "Recorded in"
        case SongAboutStore.Canon.lyricist: "Lyricist"
        case SongAboutStore.Canon.composer: "Composer"
        case SongAboutStore.Canon.producer: "Producer"
        default: "About"
        }
    }

    /// Whole sentences of [answer], never an ellipsis: the first always, then more while they fit
    /// [budget] weighted characters (CJK counts 2, `**` counts 0), up to [maxSentences].
    static func answerExcerpt(_ answer: String, budget: Int,
                              maxSentences: Int = ScrapbookRules.answerSentences) -> ScrapExcerpt {
        let sentences = splitSentences(answer.trimmingCharacters(in: .whitespacesAndNewlines))
        guard !sentences.isEmpty else { return ScrapExcerpt(text: "", truncated: false) }
        var shown = ""
        var weight = 0
        var count = 0
        for sentence in sentences {
            let w = weightedLength(sentence)
            guard count < maxSentences, count == 0 || weight + w <= budget else { break }
            shown += sentence
            weight += w
            count += 1
        }
        return ScrapExcerpt(text: shown.trimmingCharacters(in: .whitespacesAndNewlines), truncated: count < sentences.count)
    }

    /// Android SentenceRegex: a run up to its end marks (and closing quotes), or the trailing rest.
    static func splitSentences(_ text: String) -> [String] {
        let pattern = #"[^。！？.!?]+[。！？.!?]+["”’）)]*|[^。！？.!?]+$"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [text] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range) }
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    /// Characters with CJK / full-width counting 2 and markdown bold markers counting 0.
    static func weightedLength(_ text: String) -> Int {
        text.replacingOccurrences(of: "**", with: "").utf16.reduce(0) { n, c in
            n + ((0x3000...0x9FFF).contains(c) || (0xFF00...0xFFEF).contains(c) ? 2 : 1)
        }
    }

    /// "+1 note" / "+3 notes" (Android detail_scrap_hidden_notes).
    static func hiddenNotes(_ n: Int) -> String { n == 1 ? "+1 note" : "+\(n) notes" }
}
