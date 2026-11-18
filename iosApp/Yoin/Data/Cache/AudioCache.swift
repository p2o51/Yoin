import CryptoKit
import Foundation
import os

/// Which bytes of a stream are on disk: sorted, merged, half-open spans.
struct ByteRanges: Codable, Equatable, Sendable {
    struct Span: Codable, Equatable, Sendable {
        var start: Int64
        var end: Int64
    }

    private(set) var spans: [Span] = []

    /// Bytes held.
    var total: Int64 { spans.reduce(0) { $0 + ($1.end - $1.start) } }

    /// Adds [start, end); returns how many of those bytes are new.
    @discardableResult
    mutating func insert(_ start: Int64, _ end: Int64) -> Int64 {
        guard end > start else { return 0 }
        let before = total
        var merged: [Span] = []
        var new = Span(start: start, end: end)
        var placed = false
        for s in spans {
            if s.end < new.start {
                merged.append(s)
            } else if new.end < s.start {
                if !placed { merged.append(new); placed = true }
                merged.append(s)
            } else {
                new = Span(start: min(s.start, new.start), end: max(s.end, new.end))
            }
        }
        if !placed { merged.append(new) }
        spans = merged
        return total - before
    }

    /// How many bytes from [offset] on are held without a gap.
    func contiguous(from offset: Int64) -> Int64 {
        for s in spans where s.start <= offset && offset < s.end { return s.end - offset }
        return 0
    }

    /// Every byte of [0, length) is held.
    func covers(_ length: Int64) -> Bool {
        length > 0 && spans.count == 1 && spans[0].start == 0 && spans[0].end >= length
    }

    /// Drops what lies at or past [length] (a data file shorter than its record).
    mutating func clip(to length: Int64) {
        spans = spans.compactMap { s in
            s.start >= length ? nil : Span(start: s.start, end: min(s.end, length))
        }
    }
}

/// Streamed songs on disk (Android's Media3 `SimpleCache`, `media_cache`):
/// each stream is one sparse data file plus a small JSON record of which
/// bytes it holds, keyed by `SubsonicCacheKey`, bounded by a byte budget
/// with least-recently-used eviction (Android `LeastRecentlyUsedCacheEvictor`,
/// 500 MB). In Caches, so the system may purge it, like Android's cacheDir.
///
/// A stream the server serves in byte ranges (the file as it is) fills range
/// by range and is served from whatever bytes are held. A transcode has no
/// ranges: it is written front to back and kept only once whole; a partial
/// one is dropped when its player lets go of it.
///
/// Everything runs on [queue] (the resource loaders' delegate queue too);
/// the methods marked "main" hop onto it.
final class AudioCache: @unchecked Sendable {
    static let shared = AudioCache(
        directory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("YoinAudio", isDirectory: true))

    /// Android `MAX_CACHE_SIZE_BYTES`.
    static let defaultMaxBytes: Int64 = 500 * 1024 * 1024

    struct Entry: Codable, Equatable, Sendable {
        var key: String
        /// The stream's length (a transcode's is the server's estimate until it is whole).
        var length: Int64?
        /// UTI for AVFoundation.
        var contentType: String?
        /// The server answers byte ranges for it.
        var byteRanges: Bool = true
        var complete: Bool = false
        var ranges = ByteRanges()
        var lastAccess: Double = 0

        /// What can be served after a relaunch: the whole stream, or ranges of a ranged one.
        var servable: Bool { complete || byteRanges }
    }

    let queue = DispatchQueue(label: "com.gpo.yoin.audiocache")
    let directory: URL
    let maxBytes: Int64
    private let clock: () -> Date
    private let log = Logger(subsystem: "com.gpo.yoin", category: "audiocache")

    // queue only:
    private var entries: [String: Entry] = [:]
    private var leases: [String: Int] = [:]
    private var handles: [String: FileHandle] = [:]
    private var unsaved: [String: Int64] = [:]
    private var totalBytes: Int64 = 0
    private var loaded = false

    init(directory: URL, maxBytes: Int64 = AudioCache.defaultMaxBytes, clock: @escaping () -> Date = Date.init) {
        self.directory = directory
        self.maxBytes = maxBytes
        self.clock = clock
    }

    // MARK: Main-thread surface

    /// main: the bytes held (Settings › Storage).
    func sizeBytes() -> Int64 { queue.sync { loadIfNeeded(); return totalBytes } }

    /// main: the whole stream for [key] is on disk.
    func isComplete(_ key: String) -> Bool { queue.sync { loadIfNeeded(); return entries[key]?.complete == true } }

    /// main: the whole stream behind [url] is on disk.
    func isComplete(url: URL) -> Bool { SubsonicCacheKey.of(url).map(isComplete) ?? false }

    /// Reads the records in the background, so the first song needn't wait.
    func warmUp() { queue.async { self.loadIfNeeded() } }

    /// Drops every stream; one playing now restarts empty.
    func clear() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            queue.async {
                self.clearNow()
                done.resume()
            }
        }
    }

    // MARK: Queue API (resource loaders)

    /// Takes [key] for a player: it is not evicted until [release]. A
    /// partial transcode left over from before can't be served and starts over.
    @discardableResult
    func lease(_ key: String) -> Entry {
        dispatchPrecondition(condition: .onQueue(queue))
        loadIfNeeded()
        var e = entries[key] ?? Entry(key: key)
        if !e.servable, e.ranges.total > 0 { reset(&e) }
        e.lastAccess = clock().timeIntervalSince1970
        entries[key] = e
        leases[key, default: 0] += 1
        save(key)
        return e
    }

    func release(_ key: String) {
        dispatchPrecondition(condition: .onQueue(queue))
        let n = (leases[key] ?? 1) - 1
        if n > 0 { leases[key] = n; return }
        leases[key] = nil
        try? handles.removeValue(forKey: key)?.close()
        if let e = entries[key], !e.servable { remove(key); return }
        save(key)
        trim()
    }

    func entry(_ key: String) -> Entry? {
        dispatchPrecondition(condition: .onQueue(queue))
        loadIfNeeded()
        return entries[key]
    }

    /// The server's answer: a length or type unlike the record's means a
    /// different file, so the held bytes go.
    func setInfo(_ key: String, length: Int64?, contentType: String?, byteRanges: Bool) {
        dispatchPrecondition(condition: .onQueue(queue))
        guard var e = entries[key] else { return }
        if e.complete, !byteRanges { return } // a whole transcode outranks a new estimate
        if let old = e.length, let length, old != length, e.byteRanges, byteRanges { reset(&e) }
        if e.byteRanges != byteRanges { reset(&e) }
        e.length = length
        e.contentType = contentType ?? e.contentType
        e.byteRanges = byteRanges
        entries[key] = e
        save(key)
    }

    /// Tests: every write fails, as a full or unwritable disk does.
    var failWrites = false

    /// Writes [data] at [offset]. A ranged stream is whole once every byte is held. False when the bytes
    /// didn't reach the disk: the loader then serves them from memory (Codex C06).
    @discardableResult
    func write(_ key: String, at offset: Int64, _ data: Data) -> Bool {
        dispatchPrecondition(condition: .onQueue(queue))
        guard !data.isEmpty else { return true }
        guard !failWrites, var e = entries[key], let handle = handle(key) else { return false }
        do {
            try handle.seek(toOffset: UInt64(offset))
            try handle.write(contentsOf: data)
        } catch {
            log.error("write failed: \(error.localizedDescription, privacy: .public)")
            return false
        }
        let added = e.ranges.insert(offset, offset + Int64(data.count))
        totalBytes += added
        if e.byteRanges, let length = e.length, e.ranges.covers(length) { e.complete = true }
        entries[key] = e
        unsaved[key, default: 0] += added
        if e.complete || unsaved[key, default: 0] >= 1 << 20 { save(key) }
        if totalBytes > maxBytes { trim() }
        return true
    }

    /// A transcode's download ended cleanly at [length]: it is whole.
    func finishSequential(_ key: String, length: Int64) {
        dispatchPrecondition(condition: .onQueue(queue))
        guard var e = entries[key], length > 0, e.ranges.covers(length) else { return }
        e.length = length
        e.ranges.clip(to: length)
        e.complete = true
        entries[key] = e
        save(key)
        log.info("stored whole \(length) bytes")
    }

    /// Bytes held from [offset] on, without a gap.
    func available(_ key: String, from offset: Int64) -> Int64 {
        entries[key]?.ranges.contiguous(from: offset) ?? 0
    }

    func read(_ key: String, at offset: Int64, count: Int) -> Data? {
        dispatchPrecondition(condition: .onQueue(queue))
        guard count > 0, let handle = handle(key) else { return nil }
        do {
            try handle.seek(toOffset: UInt64(offset))
            return try handle.read(upToCount: count)
        } catch {
            log.error("read failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    // MARK: Internals

    private func dataURL(_ key: String) -> URL { directory.appendingPathComponent(Self.fileName(key) + ".bin") }
    private func recordURL(_ key: String) -> URL { directory.appendingPathComponent(Self.fileName(key) + ".json") }

    static func fileName(_ key: String) -> String {
        SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func handle(_ key: String) -> FileHandle? {
        if let h = handles[key] { return h }
        let url = dataURL(key)
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        guard let h = try? FileHandle(forUpdating: url) else { return nil }
        handles[key] = h
        return h
    }

    private func reset(_ e: inout Entry) {
        totalBytes -= e.ranges.total
        e.ranges = ByteRanges()
        e.complete = false
        if let h = handles[e.key] { try? h.truncate(atOffset: 0) } else { try? FileManager.default.removeItem(at: dataURL(e.key)) }
    }

    private func save(_ key: String) {
        unsaved[key] = nil
        guard let e = entries[key], let data = try? JSONEncoder().encode(e) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: recordURL(key), options: .atomic)
    }

    private func remove(_ key: String) {
        if let e = entries.removeValue(forKey: key) { totalBytes -= e.ranges.total }
        try? handles.removeValue(forKey: key)?.close()
        unsaved[key] = nil
        try? FileManager.default.removeItem(at: dataURL(key))
        try? FileManager.default.removeItem(at: recordURL(key))
    }

    /// Least recently used first, never one a player holds, until under budget.
    private func trim() {
        guard totalBytes > maxBytes else { return }
        let victims = entries.values.filter { leases[$0.key] == nil }.sorted { $0.lastAccess < $1.lastAccess }
        for e in victims {
            if totalBytes <= maxBytes { break }
            remove(e.key)
            log.info("evicted \(e.ranges.total) bytes")
        }
    }

    private func clearNow() {
        loadIfNeeded()
        for key in Array(entries.keys) {
            if leases[key] != nil, var e = entries[key] {
                reset(&e)
                entries[key] = e
                save(key)
            } else {
                remove(key)
            }
        }
        // Strays (a data file whose record never landed).
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        let live = Set(entries.keys.flatMap { [Self.fileName($0) + ".bin", Self.fileName($0) + ".json"] })
        for f in files where !live.contains(f.lastPathComponent) { try? FileManager.default.removeItem(at: f) }
        totalBytes = entries.values.reduce(0) { $0 + $1.ranges.total }
    }

    /// Reads every record once. A record whose data file is shorter keeps
    /// only the bytes the file has; a data file with no record is dropped.
    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        var recorded = Set<String>()
        for f in files where f.pathExtension == "json" {
            guard let data = try? Data(contentsOf: f), var e = try? JSONDecoder().decode(Entry.self, from: data),
                  Self.fileName(e.key) == f.deletingPathExtension().lastPathComponent else {
                try? fm.removeItem(at: f)
                continue
            }
            let bin = dataURL(e.key)
            let size = (try? bin.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }.map(Int64.init) ?? 0
            e.ranges.clip(to: size)
            if let length = e.length, e.complete, !e.ranges.covers(length) { e.complete = false }
            if !e.servable, e.ranges.total > 0 { e.ranges = ByteRanges() }
            entries[e.key] = e
            totalBytes += e.ranges.total
            recorded.insert(Self.fileName(e.key))
        }
        for f in files where f.pathExtension == "bin" && !recorded.contains(f.deletingPathExtension().lastPathComponent) {
            try? fm.removeItem(at: f)
        }
    }
}
