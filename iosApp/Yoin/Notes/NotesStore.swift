import Foundation
import Observation

/// One note on a song, anchored to the moment it was written. Android
/// `data/local/SongNote` (Room `song_notes`, `positionMs` since v27); the
/// profile is `scope`, the account it was written under (`AccountScope`).
struct SongNote: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var provider: String
    var trackId: String
    var content: String
    /// Epoch ms.
    var createdAt: Int64
    var updatedAt: Int64
    var title: String
    var artist: String
    /// Playhead (ms into the track) when the note was written; nil = no moment.
    var positionMs: Int64?
    /// The account the note belongs to (`AccountScope.scope(for:)`); nil for one-account providers and
    /// notes saved before scopes existed.
    var scope: String? = nil
}

/// The notes written on this device, kept as one JSON file in Application
/// Support (`Yoin/notes.json`). Small by nature (a few lines per song), so it
/// is read once and rewritten whole on every change.
@MainActor
@Observable
final class NotesStore {
    static let shared = NotesStore(fileURL: NotesStore.defaultURL)

    /// Every note on disk, every account's.
    private var stored: [SongNote] = []
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let scopes: AccountScope

    /// The notes of the signed-in account (another server's notes on a song with the same id stay hidden).
    var notes: [SongNote] { stored.filter(visible) }

    init(fileURL: URL, now: @escaping () -> Date = Date.init, scopes: AccountScope = .shared) {
        self.fileURL = fileURL
        self.now = now
        self.scopes = scopes
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([SongNote].self, from: data) {
            stored = saved
        }
    }

    private func visible(_ n: SongNote) -> Bool { scopes.isVisible(provider: n.provider, scope: n.scope) }

    /// Index in `stored` of the visible note [id].
    private func index(_ id: String) -> Int? { stored.firstIndex { $0.id == id && visible($0) } }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("notes.json")
    }

    /// This song's notes in timeline order (Android `NoteSortMode.Timeline`).
    func notes(provider: String, trackId: String) -> [SongNote] {
        Self.timeline(notes.filter { $0.provider == provider && $0.trackId == trackId })
    }

    /// Saves a note; blank words are ignored. Returns the stored note.
    @discardableResult
    func add(provider: String, trackId: String, content: String, positionMs: Int64?,
             title: String, artist: String) -> SongNote? {
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return nil }
        let ms = nowMs
        let note = SongNote(id: UUID().uuidString, provider: provider, trackId: trackId, content: words,
                            createdAt: ms, updatedAt: ms, title: title, artist: artist,
                            positionMs: positionMs.map { max(0, $0) }, scope: scopes.scope(for: provider))
        stored.append(note)
        persist()
        return note
    }

    /// Removes a note and hands it back (for Undo); nil when it isn't there.
    @discardableResult
    func delete(id: String) -> SongNote? {
        guard let i = index(id) else { return nil }
        let removed = stored.remove(at: i)
        persist()
        return removed
    }

    /// Puts a deleted note back as it was (Android's 撤销 after a delete).
    func restore(_ note: SongNote) {
        guard !stored.contains(where: { $0.id == note.id && $0.scope == note.scope }) else { return }
        stored.append(note)
        persist()
    }

    /// New words for a note; blank words are ignored. Returns the stored note.
    @discardableResult
    func update(id: String, content: String) -> SongNote? {
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty, let i = index(id) else { return nil }
        stored[i].content = words
        stored[i].updatedAt = nowMs
        persist()
        return stored[i]
    }

    /// Android "Align to now": the note moves to [positionMs] of its song.
    @discardableResult
    func realign(id: String, positionMs: Int64) -> SongNote? {
        guard let i = index(id) else { return nil }
        stored[i].positionMs = max(0, positionMs)
        stored[i].updatedAt = nowMs
        persist()
        return stored[i]
    }

    // MARK: Cloud sync (Data/Sync)

    /// A note added, edited, moved or deleted by the user: Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    func note(id: String) -> SongNote? { index(id).map { stored[$0] } }

    /// Writes a note that came from another device as it is (its own times and moment). False (and
    /// nothing changed) when it couldn't reach the disk: sync retries it rather than count it applied.
    @discardableResult
    func syncPut(_ note: SongNote) -> Bool {
        var note = note
        note.scope = scopes.scope(for: note.provider)
        var next = stored
        if let i = index(note.id) { next[i] = note } else { next.append(note) }
        return commit(next)
    }

    /// Removes a note another device deleted (false: not on disk, nothing changed).
    @discardableResult
    func syncRemove(id: String) -> Bool {
        guard let i = index(id) else { return true }
        var next = stored
        next.remove(at: i)
        return commit(next)
    }

    /// Notes saved before notes carried an account belong to the first Subsonic account seen after the
    /// update (AccountScope.adoptLegacyIfNeeded, once per install).
    func adoptLegacy(provider: String, scope: String) {
        guard stored.contains(where: { $0.provider == provider && $0.scope == nil }) else { return }
        stored = stored.map { n in
            var n = n
            if n.provider == provider && n.scope == nil { n.scope = scope }
            return n
        }
        persistOnly()
    }

    /// [next] on disk first, then in memory.
    private func commit(_ next: [SongNote]) -> Bool {
        guard write(next) else { return false }
        stored = next
        return true
    }

    private var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    private func persist() {
        persistOnly()
        Self.onLocalChange?()
    }

    private func persistOnly() {
        // A failed write keeps the user's notes in memory for this run.
        _ = write(stored)
    }

    private func write(_ notes: [SongNote]) -> Bool {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(notes).write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    // MARK: Rules shared with Android (`ui/component/NoteContent.kt`)

    /// Anchored notes by moment, then by when they were written; notes with
    /// no moment last.
    nonisolated static func timeline(_ notes: [SongNote]) -> [SongNote] {
        notes.sorted { a, b in
            switch (a.positionMs, b.positionMs) {
            case let (x?, y?): x != y ? x < y : a.createdAt < b.createdAt
            case (nil, nil): a.createdAt < b.createdAt
            case (_?, nil): true
            case (nil, _?): false
            }
        }
    }

    /// Android `NoteSortMode`: by song moment, or by when they were written
    /// (oldest first, a journal).
    enum SortMode: String, Sendable { case timeline, created }

    nonisolated static func sorted(_ notes: [SongNote], _ mode: SortMode) -> [SongNote] {
        switch mode {
        case .timeline: timeline(notes)
        case .created: notes.sorted { $0.createdAt < $1.createdAt }
        }
    }

    /// Android `formatNoteDate`: just now / minutes / hours / days ago, then the date.
    nonisolated static func formatDate(_ epochMs: Int64, nowMs: Int64) -> String {
        let delta = max(0, nowMs - epochMs) / 1000
        let minutes = delta / 60, hours = minutes / 60, days = hours / 24
        switch true {
        case minutes < 1: return "Just now"
        case minutes < 60: return minutes == 1 ? "1 minute ago" : "\(minutes) minutes ago"
        case hours < 24: return hours == 1 ? "1 hour ago" : "\(hours) hours ago"
        case days < 7: return days == 1 ? "1 day ago" : "\(days) days ago"
        default:
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .none
            return f.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
        }
    }

    /// The note the playhead is inside: the latest anchored note at or before
    /// [positionMs] (the lyrics' current-line rule). Nil before the first one.
    nonisolated static func currentAnchoredNoteId(_ notes: [SongNote], positionMs: Int64) -> String? {
        var best: SongNote?
        for note in notes {
            guard let anchor = note.positionMs else { continue }
            if anchor <= positionMs && (best?.positionMs ?? -1) <= anchor { best = note }
        }
        return best?.id
    }

    /// "m:ss".
    nonisolated static func formatPosition(_ ms: Int64) -> String {
        let total = max(0, ms) / 1000
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
