import Foundation
import YoinShared

/// One timed lyric line as the scrapbook sheet's Notes tab reads it (Android ScrapLyricLine).
struct ScrapLyricLine: Equatable, Sendable {
    let startMs: Int64
    let text: String
}

/// One song's lyrics for the Notes tab (Android ScrapLyrics): timed lines only, an untimed text has no
/// moments.
struct ScrapLyrics: Equatable, Sendable {
    var lines: [ScrapLyricLine]
    var loading = false

    static let none = ScrapLyrics(lines: [])
    static let loading = ScrapLyrics(lines: [], loading: true)

    /// Blank timed lines (instrumental gaps) are dropped; untimed lyrics give no lines (Android
    /// ScrapLyrics.from).
    static func from(synced: Bool, lines: [(timeMs: Int64, text: String)]) -> ScrapLyrics {
        guard synced else { return .none }
        return ScrapLyrics(lines: lines
            .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .enumerated()
            .sorted { ($0.element.timeMs, $0.offset) < ($1.element.timeMs, $1.offset) }
            .map { ScrapLyricLine(startMs: $0.element.timeMs, text: $0.element.text.trimmingCharacters(in: .whitespacesAndNewlines)) })
    }

    /// What Now Playing's loader holds for the song.
    @MainActor
    static func from(_ state: NowPlayingData.LyricsState) -> ScrapLyrics {
        switch state {
        case .loading: return .loading
        case .none: return .none
        case .lines(let l): return from(synced: l.synced, lines: l.lyrics.lines.map { ($0.timeMs, $0.text) })
        }
    }
}

/// One row of the Notes tab, top to bottom (Android ScrapNoteRow).
enum ScrapNoteRow: Equatable, Identifiable {
    /// A lyric line; `noted` when a note was taken on it.
    case lyric(index: Int, line: ScrapLyricLine, noted: Bool)
    case note(SongNote)
    /// The note being written from a lyric line (or, without timed lyrics, from nothing).
    case draft(positionMs: Int64?)
    /// Lines left out between two passages ("· · ·").
    case gap(after: Int)

    var id: String {
        switch self {
        case .lyric(let index, _, _): "line:\(index)"
        case .note(let n): "note:\(n.id)"
        case .draft: "draft"
        case .gap(let after): "gap:\(after)"
        }
    }
}

/// The pure rules the Notes tab draws by (Android AlbumScrapbookSheetModel.kt): which lyric lines go
/// around each note.
enum ScrapNoteRows {
    /// The note being written, placed like a note at its moment (nil: no moment).
    struct Draft: Equatable { var positionMs: Int64? }

    /// Lyric lines shown above and below a note (owner 2026-11-02: three each way).
    static let contextLines = 3

    /// The lyric line playing at `positionMs`: the last one started by then; -1 before the first.
    static func lyricIndexAt(_ lines: [ScrapLyricLine], _ positionMs: Int64) -> Int {
        var low = 0, high = lines.count - 1, found = -1
        while low <= high {
            let mid = (low + high) / 2
            if lines[mid].startMs <= positionMs {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /// Each timed note sits under the line it was taken on, with `context` lines above and below; passages
    /// that touch merge into one, so no line shows twice, and a gap separates the rest. `expanded` shows
    /// every line instead. Notes without a moment, or a song without timed lyrics, list the notes alone.
    static func rows(lines: [ScrapLyricLine], notes: [SongNote], expanded: Bool, draft: Draft? = nil,
                     context: Int = contextLines) -> [ScrapNoteRow] {
        guard !lines.isEmpty else {
            return notes.map { .note($0) } + (draft.map { [.draft(positionMs: $0.positionMs)] } ?? [])
        }
        // Anchored items (a note, or the draft) by the line they follow; -1 = before the first line.
        var atLine: [Int: [ScrapNoteRow]] = [:]
        var loose: [ScrapNoteRow] = []
        for note in notes {
            if let ms = note.positionMs { atLine[lyricIndexAt(lines, ms), default: []].append(.note(note)) } else { loose.append(.note(note)) }
        }
        if let draft {
            let row = ScrapNoteRow.draft(positionMs: draft.positionMs)
            if let ms = draft.positionMs { atLine[lyricIndexAt(lines, ms), default: []].append(row) } else { loose.append(row) }
        }
        let noted = Set(notes.compactMap { $0.positionMs.map { lyricIndexAt(lines, $0) } })

        var ranges: [ClosedRange<Int>] = []
        if expanded {
            ranges = [0...(lines.count - 1)]
        } else {
            for at in atLine.keys.sorted() {
                let from = max(at - context, 0)
                let to = min(max(at + context, 0), lines.count - 1)
                if let last = ranges.last, from <= last.upperBound + 1 {
                    ranges[ranges.count - 1] = last.lowerBound...max(last.upperBound, to)
                } else {
                    ranges.append(from...to)
                }
            }
        }
        var out: [ScrapNoteRow] = []
        for (i, range) in ranges.enumerated() {
            if i > 0 { out.append(.gap(after: ranges[i - 1].upperBound)) }
            if range.lowerBound == 0 { out += atLine[-1] ?? [] }
            for index in range {
                out.append(.lyric(index: index, line: lines[index], noted: noted.contains(index)))
                out += atLine[index] ?? []
            }
        }
        if !loose.isEmpty, !ranges.isEmpty { out.append(.gap(after: lines.count - 1)) }
        return out + loose
    }
}
