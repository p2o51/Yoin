import CryptoKit
import Foundation

/// A song note as it will be written (Android `ImportedNote`).
struct ImportedNote: Hashable, Sendable {
    /// `SpotoolfyImportMapper.noteId`: the same note from the same file always gets the same id.
    var id: String
    var trackId: String
    /// The note body Yoin stores: the user's text, then the lyric snippet.
    var content: String
    /// Only the user's text, for the recap's keywords.
    var text: String
    /// Lyric lines with LRC tags removed; empty when the record had none.
    var lyricLines: [String]
    var recordedAt: Int64
    var positionMs: Int64?
    var title: String?
    var artist: String?
}

/// A track's latest Spotoolfy rating on Yoin's 0-10 scale.
struct ImportedRating: Hashable, Sendable {
    var trackId: String
    var rating: Double
    var recordedAt: Int64
}

/// What one Spotoolfy file puts into an account.
struct SpotoolfyImportPlan: Sendable {
    var notes: [ImportedNote]
    var ratings: [ImportedRating]
    /// Records left out: unreadable rows, Spotoolfy's sample rows, ids that are not Spotify tracks, empty records.
    var skipped: Int
    /// Earliest and latest record that put a note or a rating in the plan; 0 when the plan is empty.
    var firstRecordAt: Int64
    var lastRecordAt: Int64
    /// Spotoolfy's `tracks` rows for the plan's tracks, by Spotify track id.
    var tracks: [String: SpotoolfyTrack]

    var isEmpty: Bool { notes.isEmpty && ratings.isEmpty }
}

/// Maps a parsed export to what Yoin stores (Android `SpotoolfyImportMapper`). Pure: no clock, no
/// account, no disk. Only what the user wrote moves: notes and ratings.
enum SpotoolfyImportMapper {
    static func map(_ export: SpotoolfyExport) -> SpotoolfyImportPlan {
        var tracks: [String: SpotoolfyTrack] = [:]
        for track in export.tracks { if let id = normalizeTrackId(track.trackId) { tracks[id] = track } }

        var skipped = export.unreadableRecords
        var notes: [ImportedNote] = []
        var noteIds = Set<String>()
        var latestRating: [String: ImportedRating] = [:]
        var first = Int64.max, last = Int64.min
        for record in export.records {
            let trackId = normalizeTrackId(record.trackId)
            let text = (record.note ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rating = record.rating.flatMap(mapRating)
            guard let trackId, !(text.isEmpty && rating == nil) else {
                skipped += 1
                continue
            }
            first = min(first, record.recordedAt)
            last = max(last, record.recordedAt)
            if let rating {
                // The newest record holds the track's current rating; on a tie the later row in the file wins.
                if let previous = latestRating[trackId], record.recordedAt < previous.recordedAt {} else {
                    latestRating[trackId] = ImportedRating(trackId: trackId, rating: rating, recordedAt: record.recordedAt)
                }
            }
            if !text.isEmpty {
                let track = tracks[trackId]
                let lines = lyricLines(record.lyricsSnapshot)
                let id = noteId(trackId: trackId, recordedAt: record.recordedAt, note: record.note ?? "")
                if noteIds.insert(id).inserted {
                    notes.append(ImportedNote(
                        id: id, trackId: trackId, content: composeContent(text, lines), text: text, lyricLines: lines,
                        recordedAt: record.recordedAt,
                        positionMs: record.positionMs.flatMap { $0 > 0 ? $0 : nil },
                        title: track?.name.flatMap { $0.isBlank ? nil : $0 },
                        artist: track?.artist.flatMap { $0.isBlank ? nil : $0 }))
                }
            }
        }
        let used = Set(notes.map(\.trackId)).union(latestRating.keys)
        // Stable by time (ties keep file order for notes, track id for ratings).
        let sortedNotes = notes.enumerated().sorted { ($0.element.recordedAt, $0.offset) < ($1.element.recordedAt, $1.offset) }
            .map(\.element)
        let sortedRatings = latestRating.values.sorted { ($0.recordedAt, $0.trackId) < ($1.recordedAt, $1.trackId) }
        return SpotoolfyImportPlan(notes: sortedNotes, ratings: sortedRatings, skipped: skipped,
                                   firstRecordAt: first == .max ? 0 : first, lastRecordAt: last == .min ? 0 : last,
                                   tracks: tracks.filter { used.contains($0.key) })
    }

    /// A bare Spotify track id, or nil: the `spotify:track:` prefix is dropped, and samples like `sample1` fail.
    static func normalizeTrackId(_ raw: String) -> String? {
        var id = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if id.hasPrefix("spotify:track:") { id.removeFirst("spotify:track:".count) }
        guard id.utf8.count == 22, id.utf8.allSatisfy({ ($0 >= 48 && $0 <= 57) || ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122) })
        else { return nil }
        return id
    }

    /// 5 -> 10, 3 -> 5, 0 -> 1: a thumbs-down stays above 0, which Yoin reads as "not rated".
    static func mapRating(_ spotoolfy: Int) -> Double? {
        switch spotoolfy {
        case 5: 10
        case 3: 5
        case 0: 1
        default: nil
        }
    }

    /// A name-based (version 3) UUID over the track, the time and the note's text, as Java's
    /// `UUID.nameUUIDFromBytes` makes it. Not Spotoolfy's row id: that is a per-device counter. The same file
    /// imported on a second device yields the same ids, so cloud sync never doubles a note.
    static func noteId(trackId: String, recordedAt: Int64, note: String) -> String {
        let hex = SHA256.hash(data: Data(note.utf8)).map { String(format: "%02x", $0) }.joined()
        var bytes = Array(Insecure.MD5.hash(data: Data("spotoolfy:v1|\(trackId)|\(recordedAt)|\(hex)".utf8)))
        bytes[6] = (bytes[6] & 0x0f) | 0x30
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let h = bytes.map { String(format: "%02x", $0) }.joined()
        let c = Array(h)
        func part(_ a: Int, _ b: Int) -> String { String(c[a..<b]) }
        return [part(0, 8), part(8, 12), part(12, 16), part(16, 20), part(20, 32)].joined(separator: "-")
    }

    /// The snapshot's lines without LRC time or metadata tags, blank lines dropped.
    static func lyricLines(_ snapshot: String?) -> [String] {
        guard let snapshot else { return [] }
        let normalized = snapshot.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        return normalized.components(separatedBy: "\n").compactMap { line in
            var s = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if let r = s.range(of: #"^(\[[^\]]*\])+"#, options: .regularExpression) { s.removeSubrange(r) }
            s = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return s.isEmpty ? nil : s
        }
    }

    /// The user's text, then a blank line and the lyric snippet in corner quotes.
    static func composeContent(_ text: String, _ lyricLines: [String]) -> String {
        lyricLines.isEmpty ? text : "\(text)\n\n「\(lyricLines.joined(separator: "\n"))」"
    }
}

extension String {
    fileprivate var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
