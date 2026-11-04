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
    }

    struct TitleRecord: Codable, Hashable, Sendable {
        var provider: String
        var albumId: String
        var title: String
        var updatedAt: Int64
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
    }

    private struct File: Codable {
        var albums: [AlbumRecord] = []
        var titles: [TitleRecord] = []
        var memories: [MemoryCopy] = []
    }

    private(set) var albums: [String: AlbumRecord] = [:]
    private(set) var titles: [String: TitleRecord] = [:]
    private(set) var memories: [String: MemoryCopy] = [:]
    @ObservationIgnored private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL), let saved = try? JSONDecoder().decode(File.self, from: data) {
            albums = Dictionary(saved.albums.map { (Self.key($0.provider, $0.albumId), $0) }, uniquingKeysWith: { $1 })
            titles = Dictionary(saved.titles.map { (Self.key($0.provider, $0.albumId), $0) }, uniquingKeysWith: { $1 })
            memories = Dictionary(saved.memories.map { (Self.key($0.provider, $0.entityType, $0.entityId), $0) },
                                  uniquingKeysWith: { $1 })
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("album-memories.json")
    }

    static func key(_ parts: String...) -> String { parts.joined(separator: "\u{1F}") }

    // MARK: Reads

    func album(provider: String, albumId: String) -> AlbumRecord? { albums[Self.key(provider, albumId)] }

    func userTitle(provider: String, albumId: String) -> String? {
        titles[Self.key(provider, albumId)]?.title.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
    }

    func memory(provider: String, entityType: String = "album", entityId: String) -> MemoryCopy? {
        memories[Self.key(provider, entityType, entityId)]
    }

    /// Android `scrapTitle`: the listener's own title, else the AI's; nil when neither exists.
    func memoryTitle(provider: String, albumId: String) -> String? {
        userTitle(provider: provider, albumId: albumId)
            ?? memory(provider: provider, entityId: albumId)?.title?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
    }

    // MARK: Writes (cloud sync)

    func putAlbum(_ r: AlbumRecord) { albums[Self.key(r.provider, r.albumId)] = r; persist() }
    func removeAlbum(provider: String, albumId: String) { albums[Self.key(provider, albumId)] = nil; persist() }
    func putTitle(_ r: TitleRecord) { titles[Self.key(r.provider, r.albumId)] = r; persist() }
    func removeTitle(provider: String, albumId: String) { titles[Self.key(provider, albumId)] = nil; persist() }
    func putMemory(_ r: MemoryCopy) { memories[Self.key(r.provider, r.entityType, r.entityId)] = r; persist() }

    private func persist() {
        let file = File(albums: Array(albums.values), titles: Array(titles.values), memories: Array(memories.values))
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(file).write(to: fileURL, options: .atomic)
        } catch {
            // Kept in memory for this run.
        }
    }
}

fileprivate extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
