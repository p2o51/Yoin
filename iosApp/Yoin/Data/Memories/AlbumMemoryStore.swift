import Foundation
import Observation

/// What the listener left on albums, beyond track ratings and notes: the
/// album rating and long review (Android `album_ratings`), the title they gave
/// the album's Memory (`album_memory_titles`) and Gemini's Memory copy / AI
/// title (`memory_copy_cache`). iOS has no editors for these yet; cloud sync
/// fills them from Android, and the album page's scrapbook shows them.
///
/// One JSON file in Application Support (`Yoin/album-memories.json`), keyed by
/// (provider, album id), the same keys Android syncs.
@MainActor
@Observable
final class AlbumMemoryStore {
    static let shared = AlbumMemoryStore(fileURL: AlbumMemoryStore.defaultURL)

    struct AlbumRecord: Codable, Hashable, Sendable {
        var provider: String
        var albumId: String
        /// 0 = no rating (Android's convention).
        var rating: Double
        var review: String?
        /// When the review text was written (epoch ms).
        var reviewUpdatedAt: Int64?
        var updatedAt: Int64
        /// The account it belongs to (`AccountScope`); nil for one-account providers and legacy rows.
        var scope: String? = nil
    }

    struct TitleRecord: Codable, Hashable, Sendable {
        var provider: String
        var albumId: String
        var title: String
        var updatedAt: Int64
        var scope: String? = nil
    }

    struct MemoryCopy: Codable, Hashable, Sendable {
        var provider: String
        var entityType: String
        var entityId: String
        var copy: String
        var promptHash: String
        var generatedAt: Int64
        var title: String?
        var titlePromptHash: String?
        var scope: String? = nil
        /// Local per-language attempts; the current generated text also occupies the synced columns above.
        var generatedByLanguage: [String: GeneratedText]? = nil
    }

    struct GeneratedText: Codable, Hashable, Sendable {
        var titleAttempted = false
        var narrationAttempted = false
        /// Generated titles stay separate from TitleRecord (the listener's own title).
        var title: String?
        var copy: String?
        var titlePromptHash: String?
        var promptHash: String?
    }

    private struct File: Codable {
        var albums: [AlbumRecord] = []
        var titles: [TitleRecord] = []
        var memories: [MemoryCopy] = []
    }

    /// Every account's rows, by `storageKey` (scope + the Android key).
    private var allAlbums: [String: AlbumRecord] = [:]
    private var allTitles: [String: TitleRecord] = [:]
    private var allMemories: [String: MemoryCopy] = [:]
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let scopes: AccountScope

    /// The signed-in account's rows, by `key(provider, albumId)` (memories: `key(provider, type, id)`).
    var albums: [String: AlbumRecord] {
        visible(allAlbums) { ($0.provider, $0.scope, Self.key($0.provider, $0.albumId)) }
    }
    var titles: [String: TitleRecord] {
        visible(allTitles) { ($0.provider, $0.scope, Self.key($0.provider, $0.albumId)) }
    }
    var memories: [String: MemoryCopy] {
        let rows = visible(allMemories) { ($0.provider, $0.scope, Self.key($0.provider, $0.entityType, $0.entityId)) }
        // Attempt-only rows are local bookkeeping, never empty copy to upload over another device's words.
        return rows.filter { !$0.value.copy.isEmpty || !($0.value.title ?? "").isEmpty }
    }

    init(fileURL: URL, scopes: AccountScope = .shared) {
        self.fileURL = fileURL
        self.scopes = scopes
        if let data = try? Data(contentsOf: fileURL), let saved = try? JSONDecoder().decode(File.self, from: data) {
            allAlbums = Dictionary(saved.albums.map { (Self.storageKey($0.scope, $0.provider, $0.albumId), $0) },
                                   uniquingKeysWith: { $1 })
            allTitles = Dictionary(saved.titles.map { (Self.storageKey($0.scope, $0.provider, $0.albumId), $0) },
                                   uniquingKeysWith: { $1 })
            allMemories = Dictionary(saved.memories.map { (Self.storageKey($0.scope, $0.provider, $0.entityType, $0.entityId), $0) },
                                     uniquingKeysWith: { $1 })
        }
    }

    private func visible<R>(_ all: [String: R], _ parts: (R) -> (provider: String, scope: String?, key: String)) -> [String: R] {
        var out: [String: R] = [:]
        for r in all.values {
            let p = parts(r)
            if scopes.isVisible(provider: p.provider, scope: p.scope) { out[p.key] = r }
        }
        return out
    }

    private static func storageKey(_ scope: String?, _ parts: String...) -> String {
        ([scope ?? ""] + parts).joined(separator: "\u{1F}")
    }

    private func current(_ provider: String) -> String? { scopes.scope(for: provider) }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("album-memories.json")
    }

    static func key(_ parts: String...) -> String { parts.joined(separator: "\u{1F}") }

    // MARK: Reads

    func album(provider: String, albumId: String) -> AlbumRecord? {
        allAlbums[Self.storageKey(current(provider), provider, albumId)]
    }

    func title(provider: String, albumId: String) -> TitleRecord? {
        allTitles[Self.storageKey(current(provider), provider, albumId)]
    }

    func userTitle(provider: String, albumId: String) -> String? {
        title(provider: provider, albumId: albumId)?.title.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
    }

    func memory(provider: String, entityType: String = "album", entityId: String) -> MemoryCopy? {
        allMemories[Self.storageKey(current(provider), provider, entityType, entityId)]
    }

    /// Android `scrapTitle`: the listener's own title, else the AI's; nil when neither exists.
    func memoryTitle(provider: String, albumId: String) -> String? {
        userTitle(provider: provider, albumId: albumId)
            ?? memory(provider: provider, entityId: albumId)?.title?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
    }

    // MARK: Writes (cloud sync)

    // Each write reaches the disk before memory; false = nothing changed (sync retries it).

    @discardableResult func putAlbum(_ r: AlbumRecord) -> Bool {
        var r = r
        r.scope = current(r.provider)
        var a = allAlbums; a[Self.storageKey(r.scope, r.provider, r.albumId)] = r; return commit(albums: a)
    }
    @discardableResult func removeAlbum(provider: String, albumId: String) -> Bool {
        var a = allAlbums; a[Self.storageKey(current(provider), provider, albumId)] = nil; return commit(albums: a)
    }
    @discardableResult func putTitle(_ r: TitleRecord) -> Bool {
        var r = r
        r.scope = current(r.provider)
        var t = allTitles; t[Self.storageKey(r.scope, r.provider, r.albumId)] = r; return commit(titles: t)
    }
    @discardableResult func removeTitle(provider: String, albumId: String) -> Bool {
        var t = allTitles; t[Self.storageKey(current(provider), provider, albumId)] = nil; return commit(titles: t)
    }
    @discardableResult func putMemory(_ r: MemoryCopy) -> Bool {
        var r = r
        r.scope = current(r.provider)
        // Cloud rows carry the shared columns only; keep this device's attempt history on import.
        if r.generatedByLanguage == nil {
            r.generatedByLanguage = memory(provider: r.provider, entityType: r.entityType, entityId: r.entityId)?.generatedByLanguage
        }
        var m = allMemories; m[Self.storageKey(r.scope, r.provider, r.entityType, r.entityId)] = r
        return commit(memories: m)
    }

    /// Rows saved before rows carried an account go to the first Subsonic account seen after the update.
    func adoptLegacy(provider: String, scope: String) {
        func adopt<R>(_ all: [String: R], _ isLegacy: (R) -> Bool, _ restamp: (R) -> (String, R)) -> [String: R]? {
            guard all.values.contains(where: isLegacy) else { return nil }
            var out: [String: R] = [:]
            for (k, r) in all {
                if isLegacy(r) { let (nk, nr) = restamp(r); out[nk] = nr } else { out[k] = r }
            }
            return out
        }
        let a = adopt(allAlbums, { $0.provider == provider && $0.scope == nil }) { r in
            var r = r; r.scope = scope; return (Self.storageKey(scope, r.provider, r.albumId), r)
        }
        let t = adopt(allTitles, { $0.provider == provider && $0.scope == nil }) { r in
            var r = r; r.scope = scope; return (Self.storageKey(scope, r.provider, r.albumId), r)
        }
        let m = adopt(allMemories, { $0.provider == provider && $0.scope == nil }) { r in
            var r = r; r.scope = scope; return (Self.storageKey(scope, r.provider, r.entityType, r.entityId), r)
        }
        guard a != nil || t != nil || m != nil else { return }
        if !commit(albums: a, titles: t, memories: m) {
            // Kept in memory for this run; written with the next change.
            if let a { allAlbums = a }
            if let t { allTitles = t }
            if let m { allMemories = m }
        }
    }

    private func commit(albums: [String: AlbumRecord]? = nil, titles: [String: TitleRecord]? = nil,
                        memories: [String: MemoryCopy]? = nil) -> Bool {
        let a = albums ?? allAlbums, t = titles ?? allTitles, m = memories ?? allMemories
        let file = File(albums: Array(a.values), titles: Array(t.values), memories: Array(m.values))
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(file).write(to: fileURL, options: .atomic)
        } catch {
            return false
        }
        if albums != nil { allAlbums = a }
        if titles != nil { allTitles = t }
        if memories != nil { allMemories = m }
        return true
    }
}

fileprivate extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
