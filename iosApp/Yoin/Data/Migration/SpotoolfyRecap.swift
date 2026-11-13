import Foundation
import NaturalLanguage

// What the recap after an import shows (Android `SpotoolfyRecap`), worked out locally from the plan.

struct RecapNote: Hashable, Sendable, Codable {
    var text: String
    var trackTitle: String?
    var artist: String?
    var writtenAt: Int64
}

struct RecapKeyword: Hashable, Sendable, Codable {
    var text: String
    var count: Int
    /// Notes using the word, newest first.
    var notes: [RecapNote]
}

struct RecapEmoji: Hashable, Sendable, Codable {
    var emoji: String
    var count: Int
}

struct RecapLyric: Hashable, Sendable, Codable {
    var lyric: String
    var trackTitle: String?
    var artist: String?
    var capturedAt: Int64
    /// What the user wrote with the snippet.
    var note: String
}

struct SpotoolfyRecap: Sendable, Codable {
    var noteCount: Int
    var trackCount: Int
    var artistCount: Int
    var ratedTrackCount: Int
    var firstRecordAt: Int64
    var lastRecordAt: Int64
    var coverUrls: [String]
    /// Most frequent first. Empty when `fallbackNotes` stand in for them.
    var keywords: [RecapKeyword]
    var emoji: [RecapEmoji]
    /// The earliest and the latest snippet, from two songs.
    var lyricSnippets: [RecapLyric]
    /// Note cards for when there are too few notes or words for a keyword burst.
    var fallbackNotes: [RecapNote]
}

/// Splits text into words. The app uses the system tokenizer (dictionary based for Chinese and Japanese);
/// tests may pass something simpler.
typealias WordSegmenter = @Sendable (String) -> [String]

enum SystemWordSegmenter {
    static let words: WordSegmenter = { text in
        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = text
        var out: [String] = []
        tokenizer.enumerateTokens(in: text.startIndex..<text.endIndex) { range, _ in
            out.append(String(text[range]))
            return true
        }
        return out
    }
}

/// Computes `SpotoolfyRecap` from an import plan. Local and pure: no network.
struct SpotoolfyRecapCalculator {
    var segmenter: WordSegmenter = SystemWordSegmenter.words

    static let minNotesForKeywords = 20
    static let minKeywords = 8
    static let minKeywordCount = 2
    static let maxKeywords = 30
    static let maxNotesPerKeyword = 3
    static let maxEmoji = 3
    static let maxLyricChars = 40
    static let maxFallbackNotes = 6
    static let maxCovers = 24
    static let maxExcerptChars = 120

    func compute(_ plan: SpotoolfyImportPlan) -> SpotoolfyRecap {
        let notes = plan.notes
        let trackIds = Set(notes.map(\.trackId) + plan.ratings.map(\.trackId))
        var artists = Set<String>()
        for id in trackIds {
            for part in (plan.tracks[id]?.artist ?? "").components(separatedBy: ", ") {
                let a = part.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if !a.isEmpty { artists.insert(a) }
            }
        }
        let words = keywords(notes)
        let sparse = notes.count < Self.minNotesForKeywords || words.count < Self.minKeywords
        return SpotoolfyRecap(
            noteCount: notes.count, trackCount: trackIds.count, artistCount: artists.count,
            ratedTrackCount: plan.ratings.count, firstRecordAt: plan.firstRecordAt, lastRecordAt: plan.lastRecordAt,
            coverUrls: coverUrls(plan), keywords: sparse ? [] : words, emoji: sparse ? [] : emoji(notes),
            lyricSnippets: lyricSnippets(notes),
            fallbackNotes: sparse ? notes.suffix(Self.maxFallbackNotes).map(Self.recapNote) : [])
    }

    /// Words used at least twice, by count; CJK words of two or more characters, Latin words of three letters.
    func keywords(_ notes: [ImportedNote]) -> [RecapKeyword] {
        var counts: [String: Int] = [:]
        var usedIn: [String: [Int]] = [:]
        for (index, note) in notes.enumerated() {
            for raw in segmenter(note.text) {
                let word = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                guard Self.isKeyword(word) else { continue }
                counts[word, default: 0] += 1
                if usedIn[word]?.last != index { usedIn[word, default: []].append(index) }
            }
        }
        return counts.filter { $0.value >= Self.minKeywordCount }
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .prefix(Self.maxKeywords)
            .map { word, count in
                RecapKeyword(text: word, count: count,
                             notes: usedIn[word, default: []].suffix(Self.maxNotesPerKeyword).reversed()
                                 .map { Self.recapNote(notes[$0]) })
            }
    }

    private static func isKeyword(_ word: String) -> Bool {
        if word.isEmpty || SpotoolfyStopwords.all.contains(word) { return false }
        let scalars = Array(word.unicodeScalars)
        if scalars.contains(where: { $0.properties.numericType == .decimal }) { return false }
        if scalars.allSatisfy(isCjk) { return scalars.count >= 2 && !word.hasSuffix("的") }
        if scalars.allSatisfy({ $0.properties.isAlphabetic && isLatin($0) }) { return scalars.count >= 3 }
        return false
    }

    private func emoji(_ notes: [ImportedNote]) -> [RecapEmoji] {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for note in notes {
            for scalar in note.text.unicodeScalars where Self.isPictograph(scalar.value) {
                let key = String(scalar)
                if counts[key] == nil { order.append(key) }
                counts[key, default: 0] += 1
            }
        }
        let ranked = order.filter { counts[$0, default: 0] >= Self.minKeywordCount }
            .enumerated().sorted { (counts[$0.element]!, $1.offset) > (counts[$1.element]!, $0.offset) }
        return ranked.prefix(Self.maxEmoji).map { RecapEmoji(emoji: $0.element, count: counts[$0.element]!) }
    }

    /// The first line of each snippet, short enough for a card; the earliest one and the latest from another song.
    private func lyricSnippets(_ notes: [ImportedNote]) -> [RecapLyric] {
        var seen = Set<String>()
        var candidates: [(note: ImportedNote, line: String)] = []
        for note in notes {
            guard let line = note.lyricLines.first, line.unicodeScalars.count <= Self.maxLyricChars,
                  seen.insert(line).inserted else { continue }
            candidates.append((note, line))
        }
        guard let first = candidates.first else { return [] }
        let latest = candidates.dropFirst().last { $0.note.trackId != first.note.trackId }
        return ([first] + (latest.map { [$0] } ?? [])).map { note, line in
            RecapLyric(lyric: line, trackTitle: note.title, artist: note.artist, capturedAt: note.recordedAt,
                       note: Self.excerpt(note.text))
        }
    }

    private func coverUrls(_ plan: SpotoolfyImportPlan) -> [String] {
        var firstSeen: [String] = []
        var seen = Set<String>()
        let pairs = (plan.notes.map { ($0.trackId, $0.recordedAt) } + plan.ratings.map { ($0.trackId, $0.recordedAt) })
            .enumerated().sorted { ($0.element.1, $0.offset) < ($1.element.1, $1.offset) }.map(\.element)
        for (trackId, _) in pairs {
            guard let url = plan.tracks[trackId]?.coverUrl, seen.insert(url).inserted else { continue }
            firstSeen.append(url)
        }
        return Array(firstSeen.prefix(Self.maxCovers))
    }

    private static func recapNote(_ n: ImportedNote) -> RecapNote {
        RecapNote(text: excerpt(n.text), trackTitle: n.title, artist: n.artist, writtenAt: n.recordedAt)
    }

    /// At most `maxExcerptChars` characters, cut on a code point and marked with an ellipsis.
    static func excerpt(_ text: String) -> String {
        let flat = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        let scalars = flat.unicodeScalars
        guard scalars.count > maxExcerptChars else { return flat }
        var cut = String.UnicodeScalarView()
        cut.append(contentsOf: scalars.prefix(maxExcerptChars))
        return String(cut).trimmingCharacters(in: .whitespaces) + "…"
    }

    private static func isCjk(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x4E00...0x9FFF, 0x3400...0x4DBF, 0x20000...0x2EBEF, 0xF900...0xFAFF, 0x3040...0x309F, 0x30A0...0x30FF,
             0x31F0...0x31FF, 0xFF66...0xFF9F, 0x30FC: true
        default: false
        }
    }

    private static func isLatin(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x41...0x5A, 0x61...0x7A, 0xAA, 0xBA, 0xC0...0x24F, 0x1E00...0x1EFF: s.value != 0xD7 && s.value != 0xF7
        default: false
        }
    }

    /// Emoji pictographs; skin tones, flags' regional letters and keycap parts are not counted on their own.
    private static func isPictograph(_ cp: UInt32) -> Bool {
        switch cp {
        case 0x1F1E6...0x1F1FF, 0x1F3FB...0x1F3FF: false
        case 0x1F300...0x1FAFF, 0x2600...0x27BF, 0x1F000...0x1F2FF: true
        case 0x2B50, 0x2B55, 0x231A, 0x231B, 0x23F0, 0x23F3: true
        default: false
        }
    }
}

/// Each Spotify account's newest Spotoolfy recap (Android Settings › Yoin › Spotoolfy recap, D15): kept when
/// an import is written, so the recap can be seen again after the welcome guide.
enum SpotoolfyRecapStore {
    static func key(_ scope: String) -> String { "spotoolfyRecap.\(scope)" }

    static func save(_ recap: SpotoolfyRecap, scope: String, defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(recap) { defaults.set(data, forKey: key(scope)) }
    }

    static func load(scope: String, defaults: UserDefaults = .standard) -> SpotoolfyRecap? {
        defaults.data(forKey: key(scope)).flatMap { try? JSONDecoder().decode(SpotoolfyRecap.self, from: $0) }
    }

    static func remove(scope: String, defaults: UserDefaults = .standard) { defaults.removeObject(forKey: key(scope)) }
}

/// A Spotoolfy export shared or opened into Yoin ("Open in Yoin", Android's share receiver, #49): the welcome
/// guide's Spotoolfy step takes it; anywhere else it is refused, as on Android (imports go into a new account).
@MainActor
@Observable
final class SpotoolfyInbox {
    static let shared = SpotoolfyInbox()
    /// A file waiting for the guide's step.
    var pending: URL?

    /// From `onOpenURL`: true when the guide is up to take it.
    @discardableResult
    func receive(_ url: URL, landingOpen: Bool) -> Bool {
        guard url.isFileURL else { return false }
        guard landingOpen else {
            PlaylistActions.shared.show(String(localized: "Spotoolfy data can only be imported in the welcome guide."))
            return false
        }
        pending = url
        return true
    }

    func take() -> URL? {
        defer { pending = nil }
        return pending
    }
}
