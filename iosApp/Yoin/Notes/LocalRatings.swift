import Foundation
import Observation

/// Android's rating scale: the Now Playing bar is 0–10 in tenths, stored
/// locally as is (`local_ratings.rating`); Subsonic gets 0–5 stars,
/// `round(rating / 2)` (`YoinRepository.storeRating`).
enum RatingScale {
    static func serverRating(local: Double) -> Int32 {
        let clamped = min(max(local, 0), 10)
        // Kotlin roundToInt: half away from zero, the same for non-negatives.
        return Int32(min(max((clamped / 2).rounded(.toNearestOrAwayFromZero), 0), 5))
    }

    /// A server's 1–5 stars on the local scale, for songs never rated here.
    static func local(fromServer stars: Int?) -> Double? {
        guard let stars, stars > 0 else { return nil }
        return Double(min(stars, 5) * 2)
    }
}

/// Ratings given on this device, per (account, provider, song). Android keeps
/// the rating locally whatever the provider does with it; so does this, in
/// Application Support as one versioned document (`Yoin/ratings-v2.json`):
/// each row holds its value (nil = not rated, 0 = a real 0.0 score), its
/// change time (epoch ms, cloud sync's last-writer-wins) and the account it
/// was given under, written in one atomic replace, so value, zero mark and
/// time can never come apart (Codex C18). Rows from before it (three files:
/// `ratings.json`, `ratings-times.json`, `ratings-zero.json`) are read once
/// together and carried over.
///
/// Rows are scoped like Android's per-profile `local_ratings`: a Subsonic row
/// belongs to the server + user it was given under (`AccountScope`), so
/// another server's song with the same id never shows it (Codex C03).
@MainActor
@Observable
final class LocalRatings {
    static let shared = LocalRatings(fileURL: LocalRatings.defaultURL)

    struct Row: Codable, Hashable, Sendable {
        /// `AccountScope.scope(for:)` when written; nil for providers with one account (and legacy rows).
        var scope: String?
        var provider: String
        var songId: String
        /// nil = not rated (never, or a clear kept so it syncs); 0 is a score.
        var rating: Double?
        /// Epoch ms; nil = saved before change times were kept (reads as time 0).
        var updatedAt: Int64?
    }

    private struct Document: Codable {
        var version: Int
        var rows: [Row]
    }

    /// Every row, by storage key (scope + provider + song).
    private var rows: [String: Row] = [:]
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let scopes: AccountScope
    @ObservationIgnored private var documentURL: URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("ratings-v2.json")
    }
    @ObservationIgnored private var timesURL: URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("ratings-times.json")
    }
    @ObservationIgnored private var zeroURL: URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("ratings-zero.json")
    }

    /// A rating changed by the user (not by cloud sync): Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    init(fileURL: URL, scopes: AccountScope = .shared) {
        self.fileURL = fileURL
        self.scopes = scopes
        if let data = try? Data(contentsOf: documentURL),
           let doc = try? JSONDecoder().decode(Document.self, from: data) {
            for row in doc.rows { rows[Self.storageKey(row)] = row }
        } else if let legacy = Self.legacyRows(fileURL: fileURL, timesURL: timesURL, zeroURL: zeroURL) {
            for row in legacy { rows[Self.storageKey(row)] = row }
            _ = write(rows)
        }
    }

    /// The three pre-v2 files, read together. A 0 without the zero mark is "not rated" (Android's DB
    /// v33 rule); a song without a time reads as time 0.
    private static func legacyRows(fileURL: URL, timesURL: URL, zeroURL: URL) -> [Row]? {
        guard let data = try? Data(contentsOf: fileURL),
              let values = try? JSONDecoder().decode([String: Double].self, from: data) else { return nil }
        let times = (try? Data(contentsOf: timesURL)).flatMap { try? JSONDecoder().decode([String: Int64].self, from: $0) } ?? [:]
        let zeros = (try? Data(contentsOf: zeroURL)).flatMap { try? JSONDecoder().decode(Set<String>.self, from: $0) } ?? []
        return values.compactMap { key, value in
            guard let p = parts(of: key) else { return nil }
            let rating: Double? = value == 0 && !zeros.contains(key) ? nil : value
            return Row(scope: nil, provider: p.provider, songId: p.songId, rating: rating, updatedAt: times[key])
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("ratings.json")
    }

    static func key(provider: String, songId: String) -> String { "\(provider):\(songId)" }

    /// (provider, songId) of a [key]; the provider never holds a colon.
    static func parts(of key: String) -> (provider: String, songId: String)? {
        guard let colon = key.firstIndex(of: ":") else { return nil }
        return (String(key[..<colon]), String(key[key.index(after: colon)...]))
    }

    private static func storageKey(scope: String?, provider: String, songId: String) -> String {
        (scope ?? "") + "\u{1F}" + key(provider: provider, songId: songId)
    }

    private static func storageKey(_ row: Row) -> String {
        storageKey(scope: row.scope, provider: row.provider, songId: row.songId)
    }

    /// The row the current account sees for [key] ("provider:songId").
    private func visibleRow(_ key: String) -> Row? {
        guard let p = Self.parts(of: key) else { return nil }
        return rows[Self.storageKey(scope: scopes.scope(for: p.provider), provider: p.provider, songId: p.songId)]
    }

    /// Every account's rows (All Memories reads across accounts, #178).
    var everyAccount: [Row] { Array(rows.values) }

    private var visibleRows: [Row] { rows.values.filter { scopes.isVisible(provider: $0.provider, scope: $0.scope) } }

    /// Every row the current account sees, "provider:songId" → value (0 for a not-rated row; see `rating(key:)`).
    var ratings: [String: Double] {
        Dictionary(visibleRows.map { (Self.key(provider: $0.provider, songId: $0.songId), $0.rating ?? 0) },
                   uniquingKeysWith: { a, _ in a })
    }

    /// Change times of the rows the current account sees (rows from before times were kept: absent).
    var updatedAt: [String: Int64] {
        Dictionary(visibleRows.compactMap { r in r.updatedAt.map { (Self.key(provider: r.provider, songId: r.songId), $0) } },
                   uniquingKeysWith: { a, _ in a })
    }

    /// The song's score; nil = not rated (never, or cleared). 0 is a score.
    func rating(provider: String, songId: String) -> Double? {
        rating(key: Self.key(provider: provider, songId: songId))
    }

    func rating(key: String) -> Double? { visibleRow(key)?.rating }

    /// A row exists for the song (rated, or cleared and kept for sync).
    func hasRow(key: String) -> Bool { visibleRow(key) != nil }

    /// [scope]'s row for the song, whichever account is in use (the rating outbox sends an account's
    /// waiting ratings only while it is in use, and reads them under its own scope).
    func row(scope: String?, provider: String, songId: String) -> Row? {
        rows[Self.storageKey(scope: scope, provider: provider, songId: songId)]
    }

    func set(_ rating: Double, provider: String, songId: String) {
        store(rating, provider: provider, songId: songId, time: Self.nowMs, keepOnFailure: true)
        Self.onLocalChange?()
    }

    /// Back to not rated (the scrapbook's Clear; nowhere else offers it).
    /// The row stays, not rated, so the clear syncs (track ratings don't
    /// propagate deletes).
    func clear(provider: String, songId: String) {
        guard rating(provider: provider, songId: songId) != nil else { return }
        store(nil, provider: provider, songId: songId, time: Self.nowMs, keepOnFailure: true)
        Self.onLocalChange?()
    }

    // MARK: Cloud sync (Data/Sync): writes that came from another device

    /// nil = the row arrived as "not rated". False (and nothing changed) when it couldn't reach the disk.
    @discardableResult
    func syncPut(_ rating: Double?, provider: String, songId: String, updatedAt time: Int64) -> Bool {
        store(rating, provider: provider, songId: songId, time: time, keepOnFailure: false)
    }

    @discardableResult
    func syncRemove(provider: String, songId: String) -> Bool {
        var next = rows
        next[Self.storageKey(scope: scopes.scope(for: provider), provider: provider, songId: songId)] = nil
        return commit(next, keepOnFailure: false)
    }

    // MARK: Accounts

    /// Rows saved before rows carried an account (scope nil) belong to the Subsonic account configured
    /// when they're first seen (AccountScope.adoptLegacyIfNeeded, once per install).
    func adoptLegacy(provider: String, scope: String) {
        var next = rows
        for (k, row) in rows where row.provider == provider && row.scope == nil {
            next[k] = nil
            var adopted = row
            adopted.scope = scope
            next[Self.storageKey(adopted)] = adopted
        }
        if next != rows { _ = commit(next, keepOnFailure: true) }
    }

    // MARK: Storage

    private static var nowMs: Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    @discardableResult
    private func store(_ rating: Double?, provider: String, songId: String, time: Int64, keepOnFailure: Bool) -> Bool {
        let row = Row(scope: scopes.scope(for: provider), provider: provider, songId: songId,
                      rating: rating.map { min(max($0, 0), 10) }, updatedAt: time)
        var next = rows
        next[Self.storageKey(row)] = row
        return commit(next, keepOnFailure: keepOnFailure)
    }

    /// [next] to disk in one atomic replace, then to memory. A user edit stays in memory for this run
    /// when the disk fails ([keepOnFailure]); a sync write doesn't, so sync retries it.
    private func commit(_ next: [String: Row], keepOnFailure: Bool) -> Bool {
        let ok = write(next)
        if ok || keepOnFailure { rows = next }
        return ok
    }

    private func write(_ rows: [String: Row]) -> Bool {
        let doc = Document(version: 2, rows: rows.values.sorted { Self.storageKey($0) < Self.storageKey($1) })
        do {
            try FileManager.default.createDirectory(at: documentURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(doc).write(to: documentURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
