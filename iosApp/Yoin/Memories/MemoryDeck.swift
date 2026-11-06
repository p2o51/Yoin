import Foundation
import SwiftUI

/// One memory (Android `MemoryEntry`): the card's exhibit — cover, groove
/// emblem, title, album row — and teaser excerpt, and what its diary reads:
/// the review, Yoin's paragraph, the rated or noted tracks with their notes,
/// the play numerals.
struct MemoryCard: Identifiable, Hashable {
    enum TitleKind: Hashable { case user, ai, album }

    struct Review: Hashable {
        var text: String
        /// When it was written (epoch ms; 0 = unknown).
        var at: Int64
    }

    /// One liner row: a rated or noted track, its notes under it (by moment, then age).
    struct DiaryTrack: Hashable, Identifiable {
        /// Its place in the album's track list (what Play starts at).
        var index: Int
        var track: Track
        var rating: Double?
        var notes: [SongNote]
        var id: String { track.id.rawId }
    }

    struct Excerpt: Hashable {
        enum Kind: Hashable { case review, note }
        var kind: Kind
        var text: String
        /// The note's track title, or the review's date ("Nov 1").
        var detail: String?
    }

    var album: Album
    /// The Memory's title, or the album name when it has none.
    var title: String
    var titleKind: TitleKind
    /// Your album rating, else your track average (the emblem's score).
    var score: Double?
    var emblemKind: ScoreEmblem.Kind
    /// One per track, in album order: rated or not (the emblem's grooves).
    var rated: [Bool]
    var excerpt: Excerpt?
    var noteCount: Int
    var lastHeardAt: Int64?
    /// The memory it was built from (a write rebuilds the card in place).
    var memory: AlbumMemory
    var review: Review?
    /// Yoin's own title under a user title (the AI's): what Restore brings back.
    var yoinTitle: String?
    /// Yoin's paragraph (the Gemini memory copy), shown above a blank page.
    var paragraph: String?
    /// The album's own note, leading the liner (Android `diaryAlbumNotes`: one).
    var albumNote: String?
    var diaryTracks: [DiaryTrack]
    var playCount: Int
    var firstPlayedAt: Int64?

    var id: String { album.id.rawId }
}

enum MemoryDeck {
    /// The deck's size (Android MEMORY_CANDIDATE_LIMIT-ish: a deck, not the library).
    static let limit = 12

    struct Inputs {
        var memories: [AlbumMemory]
        /// Raw album id → its notes (any order).
        var notes: [String: [SongNote]] = [:]
        /// Raw album id → your review and when you wrote it.
        var review: (String) -> (text: String, at: Int64)? = { _ in nil }
        /// Raw album id → (title, written by you).
        var title: (String) -> (text: String, user: Bool)? = { _ in nil }
        /// Raw song id → your rating.
        var rating: (String) -> Double? = { _ in nil }
        /// Raw album id → Yoin's own title (the AI's), whatever the user calls it.
        var yoinTitle: (String) -> String? = { _ in nil }
        /// Raw album id → Yoin's paragraph (the memory copy).
        var copy: (String) -> String? = { _ in nil }
        /// Raw album id → the album's one note (the latest album_notes row), trimmed.
        var albumNote: (String) -> String? = { _ in nil }
    }

    /// The deck: the Memory pool (eligible memories; every memory with
    /// something kept when none is), written ones first, newest write first.
    static func cards(_ x: Inputs) -> [MemoryCard] {
        var pool = x.memories.filter(\.isMemoryEligible)
        if pool.isEmpty { pool = x.memories.filter(HomeHistory.hasMemory) }
        let ordered = pool.enumerated().sorted { a, b in
            let wa = a.element.lastWrittenAt, wb = b.element.lastWrittenAt
            if (wa != nil) != (wb != nil) { return wa != nil }
            let ka = wa ?? a.element.lastPlayedAt ?? 0, kb = wb ?? b.element.lastPlayedAt ?? 0
            if ka != kb { return ka > kb }
            return a.offset < b.offset
        }.map(\.element)
        return ordered.prefix(limit).map { card($0, x) }
    }

    static func card(_ m: AlbumMemory, _ x: Inputs) -> MemoryCard {
        let id = m.albumId
        let title = x.title(id)
        let notes = (x.notes[id] ?? []).filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        // The excerpt's ladder: your review, else your newest note.
        var excerpt: MemoryCard.Excerpt?
        if let r = x.review(id), !r.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            excerpt = .init(kind: .review, text: r.text.trimmingCharacters(in: .whitespacesAndNewlines),
                            detail: r.at > 0 ? HomeHistory.memoryDate(r.at) : nil)
        } else if let n = notes.max(by: { ($0.updatedAt, $0.createdAt) < ($1.updatedAt, $1.createdAt) }) {
            excerpt = .init(kind: .note, text: n.content.trimmingCharacters(in: .whitespacesAndNewlines),
                            detail: n.title.isEmpty ? nil : n.title)
        }
        let albumScore = m.albumRating.flatMap { $0 > 0 ? $0 : nil }
        let average = m.averageSongRating
        let rated = m.album.tracks.map { x.rating($0.id.rawId) != nil }
        let review = x.review(id).flatMap { r -> MemoryCard.Review? in
            let t = r.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : .init(text: t, at: r.at)
        }
        return MemoryCard(album: m.album, title: title?.text ?? m.album.title,
                          titleKind: title == nil ? .album : (title!.user ? .user : .ai),
                          score: albumScore ?? average,
                          emblemKind: ScoreEmblem.Kind(rating: albumScore, average: average),
                          rated: rated.isEmpty ? Array(repeating: false, count: max(m.totalTracks, 1)) : rated,
                          excerpt: excerpt, noteCount: max(m.noteCount, notes.count),
                          lastHeardAt: m.lastPlayedAt, memory: m, review: review,
                          yoinTitle: x.yoinTitle(id).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 },
                          paragraph: x.copy(id).flatMap(MemoryNarrationCopy.paragraph),
                          albumNote: x.albumNote(id).flatMap { $0.isEmpty ? nil : $0 },
                          diaryTracks: diaryTracks(m.album, notes: notes, rating: x.rating),
                          playCount: m.playCount, firstPlayedAt: m.firstPlayedAt)
    }

    /// The liner (Android option A): only the rated or noted tracks, in album
    /// order, each with its notes (by moment, unanchored last, then age).
    static func diaryTracks(_ album: Album, notes: [SongNote], rating: (String) -> Double?) -> [MemoryCard.DiaryTrack] {
        var byTrack: [String: [SongNote]] = [:]
        for n in notes { byTrack[n.trackId, default: []].append(n) }
        return album.tracks.enumerated().compactMap { i, t in
            let r = rating(t.id.rawId)
            let ns = (byTrack[t.id.rawId] ?? []).sorted {
                switch ($0.positionMs, $1.positionMs) {
                case let (a?, b?) where a != b: return a < b
                case (.some, nil): return true
                case (nil, .some): return false
                default: return $0.createdAt < $1.createdAt
                }
            }
            guard r != nil || !ns.isEmpty else { return nil }
            return .init(index: i, track: t, rating: r, notes: ns)
        }
    }

    /// The foot's two numerals (Android diaryFooterStats): only for an album
    /// played in Yoin. Whole days since the first play's day.
    static func footer(plays: Int, firstPlayedAt: Int64?, now: Int64,
                       calendar: Calendar = .current) -> (plays: Int, days: Int)? {
        guard plays > 0, let first = firstPlayedAt, first > 0 else { return nil }
        let a = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(first) / 1000))
        let b = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000))
        let days = calendar.dateComponents([.day], from: a, to: b).day ?? 0
        return (plays, max(days, 0))
    }

    /// Where the deck opens: the focused album's card, else the first.
    static func startIndex(_ cards: [MemoryCard], focus albumId: String?) -> Int {
        guard let albumId else { return 0 }
        return cards.firstIndex { $0.id == albumId } ?? 0
    }

    /// A release this fast (pages / s) turns the page whatever the position.
    static let flickPagesPerSecond: CGFloat = 0.6

    /// The pager's resting page for a release at [position] (pages, fractional)
    /// moving at [velocity] (pages / s): the next page past half way, or one
    /// page on a flick (Android pager snap), clamped to the deck.
    static func settlePage(position: CGFloat, velocity: CGFloat, count: Int) -> Int {
        guard count > 0 else { return 0 }
        let page: CGFloat = if velocity > flickPagesPerSecond {
            position.rounded(.down) + 1
        } else if velocity < -flickPagesPerSecond {
            position.rounded(.up) - 1
        } else {
            position.rounded()
        }
        return Int(min(max(page, 0), CGFloat(count - 1)))
    }
}

// MARK: - The mock deck (signed out: the screenshots' library)

extension MemoryDeck {
    /// Signed out, the mock albums with ratings stand in as memories, newest first.
    /// One clock for the mock deck, so its times (and the bubble's news key) hold still.
    static let mockNow = Int64(Date().timeIntervalSince1970 * 1000)

    static func mockMemories(now: Int64 = mockNow) -> [AlbumMemory] {
        let hour: Int64 = 3_600_000
        let picks: [(Int, Int64, Int)] = [(11, 3, 2), (3, 30, 1), (0, 80, 3), (5, 200, 0), (1, 400, 1), (7, 900, 0)]
        return picks.map { i, ago, notes in
            let a = MockLibrary.allAlbums[i]
            let rated = a.tracks.compactMap(\.rating)
            return AlbumMemory(album: a, totalTracks: a.tracks.count, ratedTrackCount: rated.count,
                               coverage: Double(rated.count) / Double(max(a.tracks.count, 1)),
                               averageSongRating: rated.isEmpty ? nil : rated.reduce(0, +) / Double(rated.count),
                               albumRating: a.rating, noteCount: notes, lastWrittenAt: now - ago * hour,
                               playCount: 3 + i, firstPlayedAt: now - 3000 * hour, lastPlayedAt: now - ago * hour / 2,
                               isMemoryEligible: true)
        }
    }

    static let mockTitles: [String: String] = [
        "al12": "A field that stays bright after dark",
        "al1": "Letters I never sent",
    ]

    static let mockNotes: [String: [SongNote]] = {
        func note(_ album: Int, _ track: Int, _ text: String, _ ago: Int64) -> SongNote {
            let a = MockLibrary.allAlbums[album]
            let t = a.tracks[track]
            let at = mockNow - ago * 3_600_000
            return SongNote(id: "mock-\(album)-\(track)", provider: "mock", trackId: t.id.rawId, content: text,
                            createdAt: at, updatedAt: at, title: t.title, artist: a.artist)
        }
        return [
            "al12": [note(11, 1, "The bridge at 2:10 sounds like summer ending.", 3),
                     note(11, 0, "Morning light through the kitchen window.", 40)],
            "al4": [note(3, 1, "Night market smell, neon in the puddles.", 30)],
            "al1": [note(0, 2, "余韻 — the last chord just hangs there and I hold my breath.", 80),
                    note(0, 0, "Paper Moon on the late bus home.", 120),
                    note(0, 8, "Glass Harbor: the strings come in like a tide.", 160)],
            "al2": [note(1, 1, "Saltwater Hymn on repeat the whole ferry ride.", 400)],
        ]
    }()

    /// Yoin's own (AI) titles under the mock's; "al1" also carries a user title.
    static let mockAITitles: [String: String] = [
        "al12": "A field that stays bright after dark",
        "al1": "The chord that never resolves",
    ]

    static let mockCopy: [String: String] = [
        "al12": "Eleven plays this month, most of them after midnight. What keeps you at the bridge?",
        "al2": "You played it start to finish on one ferry ride. Which song was the crossing?",
    ]

    @MainActor static func mockInputs() -> Inputs {
        let writes = MockWrites.shared
        return Inputs(memories: mockMemories(), notes: mockNotes,
                      review: { id in
                          if let r = writes.reviews[id] { return r }
                          return id == "al4" ? ("Loud, bright and a little too short. I keep coming back to Night Market.", 0) : nil
                      },
                      title: { id in
                          if let user = writes.titles[id] { return user.map { ($0, true) } ?? mockAITitles[id].map { ($0, false) } }
                          return mockTitles[id].map { ($0, $0.hasPrefix("Letters")) }
                      },
                      rating: { id in MockLibrary.allAlbums.lazy.flatMap(\.tracks).first { $0.id.rawId == id }?.rating },
                      yoinTitle: { mockAITitles[$0] },
                      copy: { mockCopy[$0] })
    }

    /// The mock deck's writes, in memory only (signed out nothing reaches the
    /// stores): a review saved, a title named or restored (nil).
    @MainActor
    final class MockWrites {
        static let shared = MockWrites()
        var reviews: [String: (text: String, at: Int64)] = [:]
        var titles: [String: String?] = [:]
    }
}


/// Yoin's paragraph from a stored memory copy. Android caches its Gemini narration as
/// `[NARRATION]…[/NARRATION]` + `[QUESTION]…[/QUESTION]` (GeminiService.encodeMemoryNarration), and
/// cloud sync hands that form over as it is: read it the way parseMemoryNarration does, then say the
/// two in one breath. Plain prose passes through; stray markers never reach the page.
enum MemoryNarrationCopy {
    static func paragraph(_ copy: String) -> String? {
        let tagged = copy.range(of: #"\[\s*/?\s*(NARRATION|QUESTION)\s*\]"#,
                                options: [.regularExpression, .caseInsensitive]) != nil
        guard tagged else {
            // Plain prose (written on iOS): as it is, brackets in titles included.
            let plain = copy.trimmingCharacters(in: .whitespacesAndNewlines)
            return plain.isEmpty ? nil : plain
        }
        if let n = parse(copy) { return "\(n.narration) \(n.question)" }
        return clean(stripMarkers(copy))
    }

    /// Android parseMemoryNarration: both parts present and the question a question, else nil.
    static func parse(_ raw: String) -> (narration: String, question: String)? {
        guard let narration = clean(tag(raw, "NARRATION")), let question = clean(tag(raw, "QUESTION")),
              question.hasSuffix("?") || question.hasSuffix("？") else { return nil }
        return (narration, question)
    }

    /// Android extractTagContent: from `[TAG]` to `[/TAG]` (or the end), case- and space-tolerant.
    private static func tag(_ text: String, _ name: String) -> String? {
        guard let open = text.range(of: #"\[\s*\#(name)\s*\]"#, options: [.regularExpression, .caseInsensitive])
        else { return nil }
        let rest = text[open.upperBound...]
        let close = rest.range(of: #"\[\s*/\s*\#(name)\s*\]"#, options: [.regularExpression, .caseInsensitive])
        let inner = String(rest[..<(close?.lowerBound ?? rest.endIndex)])
        let stripped = stripMarkers(inner)
        return stripped.isEmpty ? nil : stripped
    }

    private static func stripMarkers(_ text: String) -> String {
        text.replacingOccurrences(of: #"\[\s*/?\s*[A-Za-z][A-Za-z0-9_]*\s*\]"#, with: "",
                                  options: .regularExpression)
    }

    /// Lines joined, spaces and wrapping quotes trimmed; nil when nothing is left.
    private static func clean(_ text: String?) -> String? {
        guard let text else { return nil }
        let joined = text.replacingOccurrences(of: #"\s*\n\s*"#, with: " ", options: .regularExpression)
        let quotes = CharacterSet(charactersIn: "\"“”").union(.whitespacesAndNewlines)
        let trimmed = joined.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: quotes)
        return trimmed.isEmpty ? nil : trimmed
    }
}
