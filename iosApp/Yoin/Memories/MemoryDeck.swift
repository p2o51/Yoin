import Foundation
import SwiftUI

/// One memory card (Android `MemoryEntry`, the card state only): the
/// exhibit — cover, groove emblem, title, album row — and the teaser excerpt.
struct MemoryCard: Identifiable, Hashable {
    enum TitleKind: Hashable { case user, ai, album }

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
        return MemoryCard(album: m.album, title: title?.text ?? m.album.title,
                          titleKind: title == nil ? .album : (title!.user ? .user : .ai),
                          score: albumScore ?? average,
                          emblemKind: ScoreEmblem.Kind(rating: albumScore, average: average),
                          rated: rated.isEmpty ? Array(repeating: false, count: max(m.totalTracks, 1)) : rated,
                          excerpt: excerpt, noteCount: max(m.noteCount, notes.count),
                          lastHeardAt: m.lastPlayedAt)
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

    static func mockInputs() -> Inputs {
        Inputs(memories: mockMemories(), notes: mockNotes,
               review: { $0 == "al4" ? ("Loud, bright and a little too short. I keep coming back to Night Market.", 0) : nil },
               title: { mockTitles[$0].map { ($0, $0.hasPrefix("Letters")) } },
               rating: { id in MockLibrary.allAlbums.lazy.flatMap(\.tracks).first { $0.id.rawId == id }?.rating })
    }
}
