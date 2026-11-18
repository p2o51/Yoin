import Foundation

// A Spotoolfy v1 export, read the way Android reads it (data/migration/spotoolfy):
// the `tracks` and `records` tables. Translations and play contexts are never
// imported, so the parser only checks that they are there.

struct SpotoolfyTrack: Hashable, Sendable {
    var trackId: String
    var name: String?
    /// Spotoolfy joins several artists with ", ".
    var artist: String?
    var album: String?
    var coverUrl: String?
}

/// One `records` row: a note, a rating, or both, written on one song at one moment.
struct SpotoolfyRecord: Hashable, Sendable {
    var trackId: String
    var note: String?
    /// 0, 3 or 5 as written; any other value is dropped by the mapper.
    var rating: Int?
    /// Playhead when the record was written.
    var positionMs: Int64?
    var recordedAt: Int64
    /// Up to three lyric lines, sometimes still carrying LRC tags.
    var lyricsSnapshot: String?
}

struct SpotoolfyExport: Sendable {
    var tracks: [SpotoolfyTrack]
    var records: [SpotoolfyRecord]
    /// Rows with no track id or no time. Counted, never logged: they may hold the user's words.
    var unreadableRecords: Int
}

enum SpotoolfyParseResult: Sendable {
    case parsed(SpotoolfyExport)
    /// Readable JSON, but not Spotoolfy's four-table export.
    case notSpotoolfy
    /// Not JSON, or cut short.
    case corrupt
    /// Over `SpotoolfyExportParser.maxBytes`.
    case tooLarge
}

/// As forgiving as Spotoolfy's own importer: a rating written as text counts as
/// neutral, times may be numbers or text, unknown keys and malformed rows are
/// skipped rather than failing the file (Android `SpotoolfyExportParser`).
enum SpotoolfyExportParser {
    /// The largest file Yoin accepts.
    static let maxBytes = 32 * 1024 * 1024

    private static let requiredKeys: Set<String> = ["tracks", "records", "translations", "play_contexts"]
    private static let ratingNeutral = 3

    static func parse(_ data: Data) -> SpotoolfyParseResult {
        if data.count > maxBytes { return .tooLarge }
        var body = data
        if body.starts(with: [0xEF, 0xBB, 0xBF]) { body = body.dropFirst(3) }
        guard let root = try? JSONSerialization.jsonObject(with: body, options: [.fragmentsAllowed]) else {
            return .corrupt
        }
        guard let object = root as? [String: Any] else { return .notSpotoolfy }
        var seen = Set<String>()
        var tracks: [SpotoolfyTrack] = []
        var records: [SpotoolfyRecord] = []
        var unreadable = 0
        for key in requiredKeys {
            guard let value = object[key] else { continue }
            guard let rows = value as? [Any] else { return .notSpotoolfy }
            seen.insert(key)
            switch key {
            case "tracks":
                tracks = rows.compactMap { ($0 as? [String: Any]).flatMap(readTrack) }
            case "records":
                for row in rows {
                    if let o = row as? [String: Any], let record = readRecord(o) { records.append(record) } else { unreadable += 1 }
                }
            default: break
            }
        }
        guard seen == requiredKeys else { return .notSpotoolfy }
        return .parsed(SpotoolfyExport(tracks: tracks, records: records, unreadableRecords: unreadable))
    }

    private static func readTrack(_ o: [String: Any]) -> SpotoolfyTrack? {
        guard let id = string(o["trackId"]), !id.trimmed.isEmpty else { return nil }
        return SpotoolfyTrack(trackId: id, name: string(o["trackName"]), artist: string(o["artistName"]),
                              album: string(o["albumName"]),
                              coverUrl: string(o["albumCoverUrl"]).flatMap { $0.hasPrefix("https://") ? $0 : nil })
    }

    private static func readRecord(_ o: [String: Any]) -> SpotoolfyRecord? {
        guard let id = string(o["trackId"]), !id.trimmed.isEmpty, let at = long(o["recordedAt"]) else { return nil }
        return SpotoolfyRecord(trackId: id, note: string(o["noteContent"]), rating: rating(o["rating"]),
                               positionMs: long(o["songTimestampMs"]), recordedAt: at,
                               lyricsSnapshot: string(o["lyricsSnapshot"]))
    }

    private static func isBool(_ n: NSNumber) -> Bool { CFGetTypeID(n) == CFBooleanGetTypeID() }

    /// A string, or a number written as its text; anything else is nil.
    private static func string(_ v: Any?) -> String? {
        if let s = v as? String { return s }
        if let n = v as? NSNumber, !isBool(n) { return numberText(n) }
        return nil
    }

    private static func numberText(_ n: NSNumber) -> String {
        let d = n.doubleValue
        if d == d.rounded(), abs(d) < 9e15 { return String(n.int64Value) }
        return n.stringValue
    }

    /// Whole numbers written as numbers or text; a fraction is cut, anything else is nil.
    private static func long(_ v: Any?) -> Int64? {
        guard let text = string(v)?.trimmed else { return nil }
        if let i = Int64(text) { return i }
        guard let d = Double(text), d.isFinite, abs(d) < 9.2e18 else { return nil }
        return Int64(d)
    }

    /// Spotoolfy's own importer rule: a rating written as text means neutral (3); a number must be whole.
    private static func rating(_ v: Any?) -> Int? {
        if v is String { return ratingNeutral }
        guard let n = v as? NSNumber, !isBool(n) else { return nil }
        let d = n.doubleValue
        guard d.isFinite, d == d.rounded(), abs(d) < 2e9 else { return nil }
        return Int(d)
    }
}

extension String {
    /// Kotlin's `trim()`.
    fileprivate var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
