import Foundation
import Observation
import os
import UIKit

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
    static let shared = LocalRatings(fileURL: LocalRatings.defaultURL, scopes: .shared, asyncWrites: true)

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

    private struct Document: Codable, Sendable {
        var version: Int
        var rows: [Row]
    }

    private struct ScopeSnapshot: Equatable {
        var subsonic: String?
        var spotify: String?
    }

    /// Every row, by storage key (scope + provider + song).
    @ObservationIgnored private var rows: [String: Row] = [:]
    @ObservationIgnored private var rowsRevision = 0
    private var collectionRevision = 0
    @ObservationIgnored private var rowSignals: [String: RatingsSignalBox] = [:]
    @ObservationIgnored private let emptySignals: [RatingsSignalBox] = (0..<64).map { _ in RatingsSignalBox() }
    @ObservationIgnored private var cachedEveryAccount: (revision: Int, value: [Row])?
    @ObservationIgnored private var cachedRatings: (revision: Int, scope: ScopeSnapshot, value: [String: Double])?
    @ObservationIgnored private var cachedUpdatedAt: (revision: Int, scope: ScopeSnapshot, value: [String: Int64])?

    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let scopes: AccountScope
    @ObservationIgnored private let asyncWrites: Bool
    @ObservationIgnored private let writer = RatingsFileWriter()
    @ObservationIgnored private var lifecycleObservers: [RatingsObserverToken] = []
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

    convenience init(fileURL: URL, scopes: AccountScope = .shared) {
        self.init(fileURL: fileURL, scopes: scopes, asyncWrites: false)
    }

    private init(fileURL: URL, scopes: AccountScope, asyncWrites: Bool) {
        self.fileURL = fileURL
        self.scopes = scopes
        self.asyncWrites = asyncWrites
        if let data = try? Data(contentsOf: documentURL),
           let doc = try? JSONDecoder().decode(Document.self, from: data) {
            for row in doc.rows { rows[Self.storageKey(row)] = row }
        } else if let legacy = Self.legacyRows(fileURL: fileURL, timesURL: timesURL, zeroURL: zeroURL) {
            for row in legacy { rows[Self.storageKey(row)] = row }
            _ = writer.writeSync(to: documentURL, entries: Self.entries(from: rows))
        }
        if asyncWrites {
            for name in [UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
                let token = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    if Thread.isMainThread {
                        MainActor.assumeIsolated { self?.writer.flushInBackground() }
                    } else {
                        Task { @MainActor [weak self] in self?.writer.flushInBackground() }
                    }
                }
                lifecycleObservers.append(RatingsObserverToken(token))
            }
        }
    }

    /// Writes any pending background snapshot to disk now.
    func flush() { writer.flush() }

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

    private static func emptySlot(for storageKey: String) -> Int {
        Int(UInt(bitPattern: storageKey.hashValue) & 63)
    }

    private var currentScopeSnapshot: ScopeSnapshot {
        ScopeSnapshot(
            subsonic: scopes.scope(for: AccountScope.subsonicProvider),
            spotify: scopes.scope(for: AccountScope.spotifyProvider)
        )
    }

    private func observeSlot(forStorageKey storageKey: String) {
        if rows[storageKey] != nil {
            let signal = rowSignals[storageKey] ?? {
                let created = RatingsSignalBox()
                rowSignals[storageKey] = created
                return created
            }()
            _ = signal.revision
        } else {
            _ = emptySignals[Self.emptySlot(for: storageKey)].revision
        }
    }

    /// The row the current account sees for [key] ("provider:songId").
    private func visibleRow(_ key: String) -> Row? {
        guard let p = Self.parts(of: key) else { return nil }
        let storageKey = Self.storageKey(scope: scopes.scope(for: p.provider), provider: p.provider, songId: p.songId)
        observeSlot(forStorageKey: storageKey)
        return rows[storageKey]
    }

    /// Every account's rows (All Memories reads across accounts, #178).
    var everyAccount: [Row] {
        _ = collectionRevision
        if let c = cachedEveryAccount, c.revision == rowsRevision { return c.value }
        let out = Array(rows.values)
        cachedEveryAccount = (rowsRevision, out)
        return out
    }

    /// Every row the current account sees, "provider:songId" → value (0 for a not-rated row; see `rating(key:)`).
    var ratings: [String: Double] {
        _ = collectionRevision
        let snap = currentScopeSnapshot
        if let c = cachedRatings, c.revision == rowsRevision, c.scope == snap { return c.value }
        var out: [String: Double] = [:]
        for r in rows.values where scopes.isVisible(provider: r.provider, scope: r.scope) {
            let k = Self.key(provider: r.provider, songId: r.songId)
            if out[k] == nil { out[k] = r.rating ?? 0 }
        }
        cachedRatings = (rowsRevision, snap, out)
        return out
    }

    /// Change times of the rows the current account sees (rows from before times were kept: absent).
    var updatedAt: [String: Int64] {
        _ = collectionRevision
        let snap = currentScopeSnapshot
        if let c = cachedUpdatedAt, c.revision == rowsRevision, c.scope == snap { return c.value }
        var out: [String: Int64] = [:]
        for r in rows.values where scopes.isVisible(provider: r.provider, scope: r.scope) {
            guard let time = r.updatedAt else { continue }
            let k = Self.key(provider: r.provider, songId: r.songId)
            if out[k] == nil { out[k] = time }
        }
        cachedUpdatedAt = (rowsRevision, snap, out)
        return out
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
        let storageKey = Self.storageKey(scope: scope, provider: provider, songId: songId)
        observeSlot(forStorageKey: storageKey)
        return rows[storageKey]
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
        let k = Self.storageKey(scope: scopes.scope(for: provider), provider: provider, songId: songId)
        var next = rows
        let existed = next.removeValue(forKey: k) != nil
        return commit(next, changedKeys: existed ? [k] : [], keepOnFailure: false)
    }

    // MARK: Accounts

    /// Rows saved before rows carried an account (scope nil) belong to the Subsonic account configured
    /// when they're first seen (AccountScope.adoptLegacyIfNeeded, once per install).
    func adoptLegacy(provider: String, scope: String) {
        var next = rows
        var changedKeys: [String] = []
        for (k, row) in rows where row.provider == provider && row.scope == nil {
            next[k] = nil
            var adopted = row
            adopted.scope = scope
            let newKey = Self.storageKey(adopted)
            next[newKey] = adopted
            changedKeys.append(k)
            changedKeys.append(newKey)
        }
        if !changedKeys.isEmpty { _ = commit(next, changedKeys: changedKeys, keepOnFailure: true) }
    }

    // MARK: Storage

    private static var nowMs: Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    @discardableResult
    private func store(_ rating: Double?, provider: String, songId: String, time: Int64, keepOnFailure: Bool) -> Bool {
        let row = Row(scope: scopes.scope(for: provider), provider: provider, songId: songId,
                      rating: rating.map { min(max($0, 0), 10) }, updatedAt: time)
        let k = Self.storageKey(row)
        var next = rows
        next[k] = row
        return commit(next, changedKeys: [k], keepOnFailure: keepOnFailure)
    }

    private func applyRows(_ next: [String: Row], changedKeys: [String]) {
        rows = next
        rowsRevision &+= 1
        for k in changedKeys {
            if next[k] != nil {
                if let signal = rowSignals[k] {
                    signal.revision &+= 1
                } else {
                    rowSignals[k] = RatingsSignalBox()
                    emptySignals[Self.emptySlot(for: k)].revision &+= 1
                }
            } else {
                if let signal = rowSignals.removeValue(forKey: k) {
                    signal.revision &+= 1
                }
                emptySignals[Self.emptySlot(for: k)].revision &+= 1
            }
        }
        collectionRevision &+= 1
    }

    private static func entries(from rows: [String: Row]) -> [RatingsEntry] {
        rows.map { RatingsEntry(key: $0.key, row: $0.value) }
    }

    /// [next] to disk in one atomic replace, then to memory. A user edit stays in memory for this run
    /// when the disk fails ([keepOnFailure]); a sync write doesn't, so sync retries it.
    private func commit(_ next: [String: Row], changedKeys: [String], keepOnFailure: Bool) -> Bool {
        let entries = Self.entries(from: next)
        if asyncWrites && keepOnFailure {
            applyRows(next, changedKeys: changedKeys)
            writer.writeAsync(to: documentURL, entries: entries)
            return true
        }
        let ok = writer.writeSync(to: documentURL, entries: entries)
        if ok || keepOnFailure {
            applyRows(next, changedKeys: changedKeys)
        }
        return ok
    }
}

@MainActor
@Observable
private final class RatingsSignalBox {
    var revision = 0
}

private struct RatingsDocument: Codable, Sendable {
    var version: Int
    var rows: [LocalRatings.Row]
}

private struct RatingsEntry: Sendable {
    let key: String
    let row: LocalRatings.Row
}

private final class RatingsObserverToken: @unchecked Sendable {
    private let token: NSObjectProtocol
    init(_ token: NSObjectProtocol) { self.token = token }
    deinit { NotificationCenter.default.removeObserver(token) }
}

private final class RatingsBackgroundTaskHolder: @unchecked Sendable {
    private let id = OSAllocatedUnfairLock<UIBackgroundTaskIdentifier>(initialState: .invalid)

    @MainActor
    func begin(name: String) {
        let taskId = UIApplication.shared.beginBackgroundTask(withName: name) { [self] in
            self.end()
        }
        id.withLock { $0 = taskId }
    }

    func end() {
        let current = id.withLock { value -> UIBackgroundTaskIdentifier in
            let current = value
            value = .invalid
            return current
        }
        guard current != .invalid else { return }
        if Thread.isMainThread {
            MainActor.assumeIsolated { UIApplication.shared.endBackgroundTask(current) }
        } else {
            DispatchQueue.main.async {
                UIApplication.shared.endBackgroundTask(current)
            }
        }
    }
}

private final class RatingsFileWriter: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.gpo.yoin.ratings", qos: .utility)

    private struct PendingJob: Sendable {
        let url: URL
        let entries: [RatingsEntry]
    }

    private struct State: Sendable {
        var pending: PendingJob?
        var scheduled = false
        var activeDrains = 0
    }

    private let state = OSAllocatedUnfairLock<State>(initialState: State())

    func writeAsync(to url: URL, entries: [RatingsEntry]) {
        let schedule = state.withLock { s in
            s.pending = PendingJob(url: url, entries: entries)
            defer { s.scheduled = true }
            return !s.scheduled
        }
        if schedule {
            queue.asyncAfter(deadline: .now() + .milliseconds(50)) { [self] in drain() }
        }
    }

    func writeSync(to url: URL, entries: [RatingsEntry]) -> Bool {
        queue.sync {
            let ok = Self.encodeAndWrite(url: url, entries: entries)
            if ok { state.withLock { $0.pending = nil } }
            return ok
        }
    }

    func flush() { queue.sync { drain() } }

    @MainActor
    func flushInBackground() {
        let hasWork = state.withLock { $0.pending != nil || $0.activeDrains > 0 }
        guard hasWork else { return }
        let holder = RatingsBackgroundTaskHolder()
        holder.begin(name: "com.gpo.yoin.ratings.flush")
        queue.async { [self, holder] in
            self.drain()
            holder.end()
        }
    }

    private func drain() {
        let job: PendingJob? = state.withLock { s in
            guard let pending = s.pending else { return nil }
            s.pending = nil
            s.scheduled = false
            s.activeDrains += 1
            return pending
        }
        guard let job else { return }
        defer { state.withLock { $0.activeDrains -= 1 } }
        _ = Self.encodeAndWrite(url: job.url, entries: job.entries)
    }

    private static func encodeAndWrite(url: URL, entries: [RatingsEntry]) -> Bool {
        let sortedRows = entries.sorted { $0.key < $1.key }.map(\.row)
        let doc = RatingsDocument(version: 2, rows: sortedRows)
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(doc).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
