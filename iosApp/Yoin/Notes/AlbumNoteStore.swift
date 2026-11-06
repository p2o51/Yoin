import Foundation
import Observation

/// An album-level note row (Android `AlbumNote`, Room `album_notes`): the same shape as a song
/// note without a moment, appended, never shared.
struct AlbumNote: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var provider: String
    var albumId: String
    var content: String
    /// Epoch ms.
    var createdAt: Int64
    var updatedAt: Int64
    var albumName: String
    var artist: String
    /// The account the note belongs to (`AccountScope.scope(for:)`); nil for one-account providers.
    var scope: String? = nil
}

/// The album notes kept on this device, one JSON file in Application Support (`Yoin/album-notes.json`).
///
/// An album has ONE album note (owner, 2026-10-05; docs/design.md: Album notes): the latest
/// non-blank row by `updatedAt`, a tie keeping the earlier-created row. Android stopped writing
/// the table ("legacy rows only") and keeps it out of Drive sync, so this is the read side Android
/// has: the scrapbook's sticky, the Memories diary's opening line and the Notes counts read it,
/// and nothing in the app writes it. `add`, `update` and `delete` are the repository's own
/// (YoinRepository.addAlbumNote / updateAlbumNote / deleteAlbumNoteById), for a source of rows
/// to come (and for tests).
@MainActor
@Observable
final class AlbumNoteStore {
    static let shared = AlbumNoteStore(fileURL: AlbumNoteStore.defaultURL)

    private var stored: [AlbumNote] = []
    @ObservationIgnored private let fileURL: URL?
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let scopes: AccountScope

    /// [fileURL] nil keeps everything in memory (tests).
    init(fileURL: URL?, now: @escaping () -> Date = Date.init, scopes: AccountScope = .shared) {
        self.fileURL = fileURL
        self.now = now
        self.scopes = scopes
        if let fileURL, let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([AlbumNote].self, from: data) {
            stored = saved
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("album-notes.json")
    }

    private func visible(_ n: AlbumNote) -> Bool { scopes.isVisible(provider: n.provider, scope: n.scope) }

    private static func isBlank(_ text: String) -> Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// This album's rows, oldest created first (the DAO's order).
    func notes(provider: String, albumId: String) -> [AlbumNote] {
        stored.filter { visible($0) && $0.provider == provider && $0.albumId == albumId }
            .sorted { $0.createdAt < $1.createdAt }
    }

    /// The album's one note: the latest non-blank row by `updatedAt` (`latestAlbumNote`); a tie keeps
    /// the earlier-created row.
    func latest(provider: String, albumId: String) -> AlbumNote? {
        var best: AlbumNote?
        for n in notes(provider: provider, albumId: albumId) where !Self.isBlank(n.content) {
            if best == nil || n.updatedAt > best!.updatedAt { best = n }
        }
        return best
    }

    /// The album's note as the scrapbook and the diary show it: trimmed, never blank.
    func text(provider: String, albumId: String) -> String? {
        latest(provider: provider, albumId: albumId)?.content.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Non-blank rows per album for [provider] (`getNoteCountsForProfile`): what Memories adds to an
    /// album's note count, and the albums it seeds a memory for.
    func counts(provider: String) -> [String: Int] {
        var out: [String: Int] = [:]
        for n in stored where visible(n) && n.provider == provider && !Self.isBlank(n.content) {
            out[n.albumId, default: 0] += 1
        }
        return out
    }

    // MARK: Writes (YoinRepository's)

    /// `addAlbumNote`: blank words are ignored. Returns the stored note; nil when it couldn't be written.
    @discardableResult
    func add(provider: String, albumId: String, albumName: String, artist: String, content: String) -> AlbumNote? {
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return nil }
        let ms = nowMs
        let note = AlbumNote(id: UUID().uuidString, provider: provider, albumId: albumId, content: words, createdAt: ms,
                             updatedAt: ms, albumName: albumName, artist: artist, scope: scopes.scope(for: provider))
        stored.append(note)
        guard write() else { stored.removeLast(); return nil }
        return note
    }

    /// `updateAlbumNote`: blank words delete the note.
    @discardableResult
    func update(id: String, content: String) -> AlbumNote? {
        guard let i = stored.firstIndex(where: { $0.id == id && visible($0) }) else { return nil }
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if words.isEmpty { delete(id: id); return nil }
        let old = stored[i]
        stored[i].content = words
        stored[i].updatedAt = nowMs
        guard write() else { stored[i] = old; return nil }
        return stored[i]
    }

    /// `deleteAlbumNoteById`.
    func delete(id: String) {
        guard let i = stored.firstIndex(where: { $0.id == id && visible($0) }) else { return }
        let old = stored.remove(at: i)
        if !write() { stored.insert(old, at: i) }
    }

    private var nowMs: Int64 { Int64(now().timeIntervalSince1970 * 1000) }

    /// True once the file holds every note (memory-only stores always do).
    private func write() -> Bool {
        guard let fileURL else { return true }
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(stored).write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
