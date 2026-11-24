import Foundation
import Observation

/// One note on a whole album (Android `data/local/AlbumNote`, Room `album_notes`): an album holds any number,
/// each a sticky on the scrapbook. Same shape as `SongNote`; the profile is `scope`, the account it was written
/// under (`AccountScope`). Synced as `album_note` (shared AlbumNoteSyncAdapter through SyncDomainBridge).
struct AlbumNote: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var provider: String
    var albumId: String
    /// The stored text: light Markdown and `[@Name](yoin:…)` mentions, kept as written.
    var content: String
    /// Epoch ms.
    var createdAt: Int64
    var updatedAt: Int64
    /// What the album was called when the note was written.
    var albumName: String
    var artist: String
    /// The account the note belongs to (`AccountScope.scope(for:)`); nil for one-account providers.
    var scope: String? = nil
}

/// The album notes on this device, every account's, kept as one JSON file in Application Support
/// (`Yoin/album_notes.json`), read once and rewritten whole (atomically) on every change. A change that
/// can't reach the disk changes nothing and reports it (nil / false), so the editor keeps its words and sync
/// retries the record.
@MainActor
@Observable
final class AlbumNotesStore {
    static let shared = AlbumNotesStore(fileURL: AlbumNotesStore.defaultURL)

    /// Every note on disk, every account's.
    @ObservationIgnored private var stored: [AlbumNote] = []
    private var revision = 0
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let scopes: AccountScope

    init(fileURL: URL, now: @escaping () -> Date = Date.init, scopes: AccountScope = .shared) {
        self.fileURL = fileURL
        self.now = now
        self.scopes = scopes
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([AlbumNote].self, from: data) {
            stored = saved
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("album_notes.json")
    }

    /// An album note added, edited, deleted or restored by the user: Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    private func visible(_ n: AlbumNote) -> Bool { scopes.isVisible(provider: n.provider, scope: n.scope) }

    /// Index in `stored` of the visible note [id].
    private func index(_ id: String) -> Int? { stored.firstIndex { $0.id == id && visible($0) } }

    private var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    /// Android AlbumNoteDao.observeForAlbum: the account in use's notes on the album, oldest first.
    func notes(provider: String, albumId: String) -> [AlbumNote] {
        _ = revision
        return Self.oldestFirst(stored.filter { $0.provider == provider && $0.albumId == albumId && visible($0) })
    }

    /// Every account's notes.
    var everyAccount: [AlbumNote] {
        _ = revision
        return stored
    }

    /// The account in use's note [id].
    func note(id: String) -> AlbumNote? {
        _ = revision
        return index(id).map { stored[$0] }
    }

    /// Android YoinRepository.addAlbumNote: trimmed words, blank writes nothing. Nil when not written.
    @discardableResult
    func add(provider: String, albumId: String, content: String, albumName: String, artist: String) -> AlbumNote? {
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return nil }
        let ms = nowMs
        let note = AlbumNote(id: UUID().uuidString.lowercased(), provider: provider, albumId: albumId, content: words,
                             createdAt: ms, updatedAt: ms, albumName: albumName, artist: artist,
                             scope: scopes.scope(for: provider))
        var next = stored
        next.append(note)
        guard commit(next, local: true) else { return nil }
        return note
    }

    /// Android editAlbumNoteById: new words for a note; blank deletes it. Returns the note as it was (for Undo
    /// after a delete); nil when there is no such note or the change couldn't be written (nothing changed).
    /// The same words (only whitespace around them may differ) write nothing: the stored text, an Android
    /// note's mentions and markup included, stays byte for byte.
    @discardableResult
    func edit(id: String, content: String) -> AlbumNote? {
        guard let i = index(id) else { return nil }
        let was = stored[i]
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if words.isEmpty { return delete(id: id) }
        if NotesStore.sameWords(content, was.content) { return was }
        var next = stored
        next[i].content = words
        next[i].updatedAt = nowMs
        guard commit(next, local: true) else { return nil }
        return was
    }

    /// Removes a note and hands it back (for Undo); nil when it isn't there or couldn't be written.
    @discardableResult
    func delete(id: String) -> AlbumNote? {
        guard let i = index(id) else { return nil }
        var next = stored
        let removed = next.remove(at: i)
        guard commit(next, local: true) else { return nil }
        return removed
    }

    /// Android restoreAlbumNote (Undo): the note back with its id and creation time, stamped now so it
    /// outlives the delete cloud sync may already have sent. False when it couldn't be written.
    @discardableResult
    func restore(_ note: AlbumNote) -> Bool {
        guard !stored.contains(where: { $0.id == note.id }) else { return true }
        var back = note
        back.updatedAt = max(nowMs, note.updatedAt + 1)
        var next = stored
        next.append(back)
        return commit(next, local: true)
    }

    // MARK: Cloud sync (SyncDomainBridge)

    /// The note [id] under whichever account it was written (ids are global, as on Android).
    func syncNote(id: String) -> AlbumNote? {
        _ = revision
        return stored.first { $0.id == id }
    }

    /// Writes a note another device wrote, as it is (its own text and times), under the account in use (the
    /// bridge reads as the row's account). False, and nothing changed, when it couldn't reach the disk.
    @discardableResult
    func syncPut(_ note: AlbumNote) -> Bool {
        var note = note
        note.scope = scopes.scope(for: note.provider)
        var next = stored
        if let i = index(note.id) {
            next[i] = note
        } else {
            next.append(note)
        }
        return commit(next, local: false)
    }

    /// Removes a note another device deleted (false: not on disk, nothing changed).
    @discardableResult
    func syncRemove(id: String) -> Bool {
        guard let i = index(id) else { return true }
        var next = stored
        next.remove(at: i)
        return commit(next, local: false)
    }

    // MARK: Disk

    /// [next] on disk, then in memory; [local] = the user's change (sync's own writes never re-trigger it).
    private func commit(_ next: [AlbumNote], local: Bool) -> Bool {
        guard Self.write(next, to: fileURL) else { return false }
        stored = next
        revision &+= 1
        if local { Self.onLocalChange?() }
        return true
    }

    nonisolated private static func write(_ notes: [AlbumNote], to url: URL) -> Bool {
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(notes).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    /// Android `ORDER BY createdAt ASC` (ties by id, so every device stacks them alike).
    nonisolated static func oldestFirst(_ notes: [AlbumNote]) -> [AlbumNote] {
        notes.sorted { $0.createdAt != $1.createdAt ? $0.createdAt < $1.createdAt : $0.id < $1.id }
    }
}
