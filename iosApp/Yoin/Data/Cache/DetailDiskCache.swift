import CryptoKit
import Foundation
import os

/// Persistent cache for album / artist / playlist pages and the Home and
/// Library lists (Android `DetailCacheStore` + `HomeSnapshotStore`): one
/// JSON file per (account, kind, id), bounded by a byte budget.
///
/// - LRU by last access (a served file is touched) once the total passes
///   [maxBytes], down to [trimTargetBytes] (5/6 of the cap: trimming only to
///   the cap would put the next write over it again).
/// - A file older than [maxAge] (30 days) is never served and gets purged.
/// - Account-scoped through the key, so a switch never shows one account's
///   pages in another.
/// - Writes carry the epoch their load began in; [remove] records the epoch
///   of the edit that dropped the entry, and a write from before it is
///   skipped, so a load in flight never puts back what an edit took out
///   (Android's generation-guarded invalidation).
/// - A file that no longer decodes is deleted and read as a miss.
///
/// In Application Support, out of iCloud backup: the system doesn't purge
/// it the way it may purge Caches (Android keeps it in Room / noBackupFilesDir).
actor DetailDiskCache {
    static let shared = DetailDiskCache(
        directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("YoinDetailCache", isDirectory: true))

    /// ~24 MB (Android `DEFAULT_MAX_BYTES`).
    static let defaultMaxBytes: Int64 = 24 * 1024 * 1024
    /// 30 days (Android `DEFAULT_MAX_AGE_MS`).
    static let defaultMaxAge: TimeInterval = 30 * 24 * 60 * 60

    enum Kind: String, Sendable {
        case album, artist, playlist
        /// Home's server lists and Library's lists (Android's Home snapshot).
        case home, list
    }

    struct Hit<V: Sendable>: Sendable {
        var cachedAt: Date
        var value: V
    }

    private struct Envelope<V: Codable>: Codable {
        var cachedAt: Double
        var value: V
    }

    let directory: URL
    let maxBytes: Int64
    let trimTargetBytes: Int64
    let maxAge: TimeInterval
    private let clock: @Sendable () -> Date
    private let log = Logger(subsystem: "com.gpo.yoin", category: "detailcache")

    /// Running total of the files' sizes: seeded with one directory scan on
    /// the first write, kept in step after.
    private var totalBytes: Int64?
    /// Per file: the epoch of the last edit that dropped it.
    private var droppedAt: [String: Int] = [:]

    init(directory: URL, maxBytes: Int64 = DetailDiskCache.defaultMaxBytes, maxAge: TimeInterval = DetailDiskCache.defaultMaxAge,
         clock: @escaping @Sendable () -> Date = Date.init) {
        self.directory = directory
        self.maxBytes = maxBytes
        trimTargetBytes = maxBytes * 5 / 6
        self.maxAge = maxAge
        self.clock = clock
    }

    func read<V: Codable & Sendable>(_ type: V.Type, scope: String, kind: Kind, id: String) -> Hit<V>? {
        let url = fileURL(scope, kind, id)
        guard let data = try? Data(contentsOf: url) else { return nil }
        guard let env = try? JSONDecoder().decode(Envelope<V>.self, from: data) else {
            log.error("corrupt \(kind.rawValue, privacy: .public) entry dropped")
            delete(url)
            return nil
        }
        let cachedAt = Date(timeIntervalSince1970: env.cachedAt)
        if clock().timeIntervalSince(cachedAt) > maxAge {
            delete(url)
            return nil
        }
        // LRU touch: only a row actually served.
        try? FileManager.default.setAttributes([.modificationDate: clock()], ofItemAtPath: url.path)
        return Hit(cachedAt: cachedAt, value: env.value)
    }

    /// Stores [value], unless an edit dropped the entry after [epoch].
    func write<V: Codable & Sendable>(_ value: V, scope: String, kind: Kind, id: String, epoch: Int) {
        let name = fileName(scope, kind, id)
        if let dropped = droppedAt[name], dropped > epoch { return }
        guard let data = try? JSONEncoder().encode(Envelope(cachedAt: clock().timeIntervalSince1970, value: value)) else { return }
        let url = directory.appendingPathComponent(name)
        ensureDirectory()
        let before = totalBytes ?? scanTotal()
        let old = size(url)
        do {
            try data.write(to: url, options: .atomic)
            // Access time on the cache's own clock (the LRU order).
            try? FileManager.default.setAttributes([.modificationDate: clock()], ofItemAtPath: url.path)
        } catch {
            log.error("write failed: \(error.localizedDescription, privacy: .public)")
            totalBytes = nil
            return
        }
        var total = max(0, before - old + Int64(data.count))
        if total > maxBytes { total = trim(keeping: name) }
        totalBytes = total
    }

    /// Drops an entry (an edit changed it) and keeps older loads from writing it back.
    func remove(scope: String, kind: Kind, id: String, epoch: Int) {
        let name = fileName(scope, kind, id)
        droppedAt[name] = max(droppedAt[name] ?? 0, epoch)
        delete(directory.appendingPathComponent(name))
    }

    /// Hygiene pass (once on start): anything past [maxAge] goes.
    func purgeExpired() {
        let cutoff = clock().addingTimeInterval(-maxAge)
        for (url, _, _) in files() {
            guard let data = try? Data(contentsOf: url),
                  let at = try? JSONDecoder().decode(CachedAtOnly.self, from: data).cachedAt else { delete(url); continue }
            if Date(timeIntervalSince1970: at) < cutoff { delete(url) }
        }
    }

    func sizeBytes() -> Int64 { totalBytes ?? scanTotal() }

    func clear() {
        for (url, _, _) in files() { delete(url) }
        totalBytes = 0
    }

    // MARK: Internals

    private struct CachedAtOnly: Decodable { var cachedAt: Double }

    private func fileName(_ scope: String, _ kind: Kind, _ id: String) -> String {
        let digest = SHA256.hash(data: Data("\(scope)\u{1F}\(kind.rawValue)\u{1F}\(id)".utf8))
        return kind.rawValue + "-" + digest.prefix(16).map { String(format: "%02x", $0) }.joined() + ".json"
    }

    private func fileURL(_ scope: String, _ kind: Kind, _ id: String) -> URL {
        directory.appendingPathComponent(fileName(scope, kind, id))
    }

    private var directoryReady = false

    private func ensureDirectory() {
        guard !directoryReady else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var dir = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        directoryReady = true
    }

    private func size(_ url: URL) -> Int64 {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }.map(Int64.init) ?? 0
    }

    private func delete(_ url: URL) {
        let n = size(url)
        if (try? FileManager.default.removeItem(at: url)) != nil, let t = totalBytes { totalBytes = max(0, t - n) }
    }

    private func files() -> [(URL, Int64, Date)] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys)) ?? []
        return urls.filter { $0.pathExtension == "json" }.map { u in
            let v = try? u.resourceValues(forKeys: Set(keys))
            return (u, Int64(v?.fileSize ?? 0), v?.contentModificationDate ?? .distantPast)
        }
    }

    private func scanTotal() -> Int64 {
        let t = files().reduce(Int64(0)) { $0 + $1.1 }
        totalBytes = t
        return t
    }

    /// Oldest-touched first until under [trimTargetBytes]; never [keeping]
    /// (the file just written). Returns the total after.
    private func trim(keeping: String) -> Int64 {
        var all = files().sorted { $0.2 < $1.2 }
        var total = all.reduce(Int64(0)) { $0 + $1.1 }
        all.removeAll { $0.0.lastPathComponent == keeping }
        var dropped = 0
        for (url, n, _) in all {
            if total <= trimTargetBytes { break }
            try? FileManager.default.removeItem(at: url)
            total -= n
            dropped += 1
        }
        log.info("trimmed \(dropped) entries")
        return total
    }
}
