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
        let ms = Int64((now().timeIntervalSince1970 * 1000).rounded())
        let note = SongNote(id: UUID().uuidString, provider: provider, trackId: trackId, content: words,
                            createdAt: ms, updatedAt: ms, title: title, artist: artist,
                            positionMs: positionMs.map { max(0, $0) })
        notes.append(note)
        persist()
        return note
    }

    func delete(id: String) {
        notes.removeAll { $0.id == id }
        persist()
    }

    private func persist() {
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
