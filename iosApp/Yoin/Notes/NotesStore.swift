import Foundation
import Observation

/// One note on a song, anchored to the moment it was written. Android
/// `data/local/SongNote` (Room `song_notes`, `positionMs` since v27), minus
/// the profile id: iOS has one server for now.
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
}

/// The notes written on this device, kept as one JSON file in Application
/// Support (`Yoin/notes.json`). Small by nature (a few lines per song), so it
/// is read once and rewritten whole on every change.
@MainActor
@Observable
final class NotesStore {
    static let shared = NotesStore(fileURL: NotesStore.defaultURL)

    private(set) var notes: [SongNote] = []
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let now: () -> Date

    init(fileURL: URL, now: @escaping () -> Date = Date.init) {
        self.fileURL = fileURL
        self.now = now
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([SongNote].self, from: data) {
            notes = saved
        }
    }

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
                            positionMs: positionMs.map { max(0, $0) })
        notes.append(note)
        persist()
        return note
    }

    /// Removes a note and hands it back (for Undo); nil when it isn't there.
    @discardableResult
    func delete(id: String) -> SongNote? {
        guard let i = notes.firstIndex(where: { $0.id == id }) else { return nil }
        let removed = notes.remove(at: i)
        persist()
        return removed
    }

    /// Puts a deleted note back as it was (Android's 撤销 after a delete).
    func restore(_ note: SongNote) {
        guard !notes.contains(where: { $0.id == note.id }) else { return }
        notes.append(note)
        persist()
    }

    /// New words for a note; blank words are ignored. Returns the stored note.
    @discardableResult
    func update(id: String, content: String) -> SongNote? {
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty, let i = notes.firstIndex(where: { $0.id == id }) else { return nil }
        notes[i].content = words
        notes[i].updatedAt = nowMs
        persist()
        return notes[i]
    }

    /// Android "Align to now": the note moves to [positionMs] of its song.
    @discardableResult
    func realign(id: String, positionMs: Int64) -> SongNote? {
        guard let i = notes.firstIndex(where: { $0.id == id }) else { return nil }
        notes[i].positionMs = max(0, positionMs)
        notes[i].updatedAt = nowMs
        persist()
        return notes[i]
    }

    // MARK: Cloud sync (Data/Sync)

    /// A note added, edited, moved or deleted by the user: Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    func note(id: String) -> SongNote? { notes.first { $0.id == id } }

    /// Writes a note that came from another device as it is (its own times and moment).
    func syncPut(_ note: SongNote) {
        if let i = notes.firstIndex(where: { $0.id == note.id }) { notes[i] = note } else { notes.append(note) }
        persistOnly()
    }

    /// Removes a note another device deleted.
    func syncRemove(id: String) {
        guard let i = notes.firstIndex(where: { $0.id == id }) else { return }
        notes.remove(at: i)
        persistOnly()
    }

    private var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    private func persist() {
        persistOnly()
        Self.onLocalChange?()
    }

    private func persistOnly() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(notes).write(to: fileURL, options: .atomic)
        } catch {
            // A failed write keeps the notes in memory for this run.
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
