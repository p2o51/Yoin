import Foundation
import Observation
import os
import UIKit

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
    static let shared = NotesStore(fileURL: NotesStore.defaultURL, now: Date.init, scopes: .shared, asyncWrites: true)

    /// Every note on disk, every account's.
    @ObservationIgnored private var stored: [SongNote] = []
    private var allRevision = 0
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let scopes: AccountScope
    @ObservationIgnored private let asyncWrites: Bool
    @ObservationIgnored private let writer = NotesFileWriter()
    @ObservationIgnored private var trackIndex: [TrackKey: NotesTrackEntry] = [:]
    @ObservationIgnored private let emptySignals: [NotesSignalBox] = (0..<64).map { _ in NotesSignalBox() }
    @ObservationIgnored private var visibleNotesCache: (revision: Int, scope: ScopeSnapshot, notes: [SongNote])?
    @ObservationIgnored private var lifecycleObservers: [NotesObserverToken] = []

    private struct TrackKey: Hashable {
        var provider: String
        var scope: String?
        var trackId: String
    }

    private struct ScopeSnapshot: Equatable {
        var subsonic: String?
        var spotify: String?
    }

    /// The notes of the signed-in account (another server's notes on a song with the same id stay hidden).
    var notes: [SongNote] {
        let rev = allRevision
        let snap = currentScopeSnapshot
        if let c = visibleNotesCache, c.revision == rev, c.scope == snap { return c.notes }
        let filtered = stored.filter(visible)
        visibleNotesCache = (rev, snap, filtered)
        return filtered
    }

    /// Every account's notes (All Memories reads across accounts, #178).
    var everyAccount: [SongNote] {
        _ = allRevision
        return stored
    }

    convenience init(fileURL: URL, now: @escaping () -> Date = Date.init, scopes: AccountScope = .shared) {
        self.init(fileURL: fileURL, now: now, scopes: scopes, asyncWrites: false)
    }

    private init(fileURL: URL, now: @escaping () -> Date, scopes: AccountScope, asyncWrites: Bool) {
        self.fileURL = fileURL
        self.now = now
        self.scopes = scopes
        self.asyncWrites = asyncWrites
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([SongNote].self, from: data) {
            stored = saved
            buildTrackIndex()
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
                lifecycleObservers.append(NotesObserverToken(token))
            }
        }
    }

    /// Writes any pending background snapshot to disk now.
    func flush() { writer.flush() }

    private var currentScopeSnapshot: ScopeSnapshot {
        ScopeSnapshot(
            subsonic: scopes.scope(for: AccountScope.subsonicProvider),
            spotify: scopes.scope(for: AccountScope.spotifyProvider)
        )
    }

    private static func emptySlot(for key: TrackKey) -> Int {
        Int(UInt(bitPattern: key.hashValue) & 63)
    }

    private func buildTrackIndex() {
        var grouped: [TrackKey: [SongNote]] = [:]
        for note in stored {
            let key = TrackKey(provider: note.provider, scope: note.scope, trackId: note.trackId)
            grouped[key, default: []].append(note)
        }
        trackIndex = grouped.mapValues { NotesTrackEntry(notes: Self.timeline($0)) }
    }

    private func indexInsert(_ note: SongNote) {
        let key = TrackKey(provider: note.provider, scope: note.scope, trackId: note.trackId)
        if let entry = trackIndex[key] {
            entry.notes = Self.timeline(entry.notes + [note])
            entry.revision &+= 1
        } else {
            trackIndex[key] = NotesTrackEntry(notes: [note])
            emptySignals[Self.emptySlot(for: key)].revision &+= 1
        }
    }

    private func indexUpdate(_ note: SongNote) {
        let key = TrackKey(provider: note.provider, scope: note.scope, trackId: note.trackId)
        guard let entry = trackIndex[key], let i = entry.notes.firstIndex(where: { $0.id == note.id }) else { return }
        entry.notes[i] = note
        entry.notes = Self.timeline(entry.notes)
        entry.revision &+= 1
    }

    private func indexRemove(id: String, key: TrackKey) {
        guard let entry = trackIndex[key] else { return }
        entry.notes.removeAll { $0.id == id }
        if entry.notes.isEmpty {
            trackIndex[key] = nil
            entry.revision &+= 1
            emptySignals[Self.emptySlot(for: key)].revision &+= 1
        } else {
            entry.revision &+= 1
        }
    }

    private func visible(_ n: SongNote) -> Bool { scopes.isVisible(provider: n.provider, scope: n.scope) }

    /// Index in `stored` of the visible note [id].
    private func index(_ id: String) -> Int? { stored.firstIndex { $0.id == id && visible($0) } }

    /// Whether an edit left the words as they were (only whitespace around them may differ): then nothing
    /// is written and the stored text, mentions and markup included, is kept exactly as it was.
    nonisolated static func sameWords(_ edited: String, _ stored: String?) -> Bool {
        guard let stored else { return false }
        return edited == stored
            || edited.trimmingCharacters(in: .whitespacesAndNewlines) == stored.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("notes.json")
    }

    /// This song's notes in timeline order (Android `NoteSortMode.Timeline`).
    func notes(provider: String, trackId: String) -> [SongNote] {
        let scope = scopes.scope(for: provider)
        let key = TrackKey(provider: provider, scope: scope, trackId: trackId)
        if let entry = trackIndex[key] {
            _ = entry.revision
            return entry.notes
        }
        _ = emptySignals[Self.emptySlot(for: key)].revision
        return []
    }

    /// Saves a note; blank words are ignored. Returns the stored note; nil when it couldn't be written.
    @discardableResult
    func add(provider: String, trackId: String, content: String, positionMs: Int64?,
             title: String, artist: String) -> SongNote? {
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return nil }
        let ms = nowMs
        let note = SongNote(id: UUID().uuidString, provider: provider, trackId: trackId, content: words,
                            createdAt: ms, updatedAt: ms, title: title, artist: artist,
                            positionMs: positionMs.map { max(0, $0) }, scope: scopes.scope(for: provider))
        var next = stored
        next.append(note)
        // Not on disk: nothing is kept, so the editor keeps the words (Codex, 2026-11-06).
        guard writeUserChange(next) else { return nil }
        stored = next
        indexInsert(note)
        allRevision &+= 1
        Self.onLocalChange?()
        return note
    }

    /// Removes a note and hands it back (for Undo); nil when it isn't there or the change couldn't be
    /// written (then nothing changed).
    @discardableResult
    func delete(id: String) -> SongNote? {
        guard let i = index(id) else { return nil }
        var next = stored
        let removed = next.remove(at: i)
        guard writeUserChange(next) else { return nil }
        stored = next
        indexRemove(id: removed.id, key: TrackKey(provider: removed.provider, scope: removed.scope, trackId: removed.trackId))
        allRevision &+= 1
        Self.onLocalChange?()
        return removed
    }

    /// Puts a deleted note back as it was (Android's 撤销 after a delete). False when it couldn't be written.
    @discardableResult
    func restore(_ note: SongNote) -> Bool {
        guard !stored.contains(where: { $0.id == note.id && $0.scope == note.scope }) else { return true }
        var next = stored
        next.append(note)
        guard writeUserChange(next) else { return false }
        stored = next
        indexInsert(note)
        allRevision &+= 1
        Self.onLocalChange?()
        return true
    }

    /// New words for a note; blank words are ignored. Returns the stored note; nil when it couldn't be
    /// written (the note keeps its old words).
    @discardableResult
    func update(id: String, content: String) -> SongNote? {
        let words = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty, let i = index(id) else { return nil }
        // The same words: the stored text stays byte for byte (an Android note's markup, its spacing).
        if Self.sameWords(content, stored[i].content) { return stored[i] }
        var next = stored
        next[i].content = words
        next[i].updatedAt = nowMs
        guard writeUserChange(next) else { return nil }
        stored = next
        let updated = stored[i]
        indexUpdate(updated)
        allRevision &+= 1
        Self.onLocalChange?()
        return updated
    }

    /// Android "Align to now": the note moves to [positionMs] of its song (nil: not written, not moved).
    @discardableResult
    func realign(id: String, positionMs: Int64) -> SongNote? {
        guard let i = index(id) else { return nil }
        var next = stored
        next[i].positionMs = max(0, positionMs)
        next[i].updatedAt = nowMs
        guard writeUserChange(next) else { return nil }
        stored = next
        let updated = stored[i]
        indexUpdate(updated)
        allRevision &+= 1
        Self.onLocalChange?()
        return updated
    }

    // MARK: Cloud sync (Data/Sync)

    /// A note added, edited, moved or deleted by the user: Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    func note(id: String) -> SongNote? {
        _ = allRevision
        return index(id).map { stored[$0] }
    }

    /// The note [id] under whichever account it was written (sync reads every account's; Android looks a
    /// note up by its UUID alone).
    func syncNote(id: String) -> SongNote? {
        _ = allRevision
        return stored.first { $0.id == id }
    }

    /// Writes a note that came from another device as it is (its own times and moment). False (and
    /// nothing changed) when it couldn't reach the disk: sync retries it rather than count it applied.
    @discardableResult
    func syncPut(_ note: SongNote) -> Bool {
        var note = note
        note.scope = scopes.scope(for: note.provider)
        var next = stored
        let old: SongNote?
        if let i = index(note.id) {
            old = stored[i]
            next[i] = note
        } else {
            old = nil
            next.append(note)
        }
        guard writer.writeSync(to: fileURL, notes: next) else { return false }
        stored = next
        if let old {
            let oldKey = TrackKey(provider: old.provider, scope: old.scope, trackId: old.trackId)
            let newKey = TrackKey(provider: note.provider, scope: note.scope, trackId: note.trackId)
            if oldKey == newKey {
                indexUpdate(note)
            } else {
                indexRemove(id: old.id, key: oldKey)
                indexInsert(note)
            }
        } else {
            indexInsert(note)
        }
        allRevision &+= 1
        return true
    }

    /// Removes a note another device deleted (false: not on disk, nothing changed).
    @discardableResult
    func syncRemove(id: String) -> Bool {
        guard let i = index(id) else { return true }
        var next = stored
        let removed = next.remove(at: i)
        guard writer.writeSync(to: fileURL, notes: next) else { return false }
        stored = next
        indexRemove(id: removed.id, key: TrackKey(provider: removed.provider, scope: removed.scope, trackId: removed.trackId))
        allRevision &+= 1
        return true
    }

    /// Notes saved before notes carried an account belong to the first Subsonic account seen after the
    /// update (AccountScope.adoptLegacyIfNeeded, once per install).
    func adoptLegacy(provider: String, scope: String) {
        guard stored.contains(where: { $0.provider == provider && $0.scope == nil }) else { return }
        var next: [SongNote] = []
        next.reserveCapacity(stored.count)
        var changed: [(old: SongNote, new: SongNote)] = []
        for n in stored {
            if n.provider == provider && n.scope == nil {
                var adopted = n
                adopted.scope = scope
                next.append(adopted)
                changed.append((n, adopted))
            } else {
                next.append(n)
            }
        }
        stored = next
        for pair in changed {
            indexRemove(id: pair.old.id, key: TrackKey(provider: pair.old.provider, scope: pair.old.scope, trackId: pair.old.trackId))
            indexInsert(pair.new)
        }
        allRevision &+= 1
        persistOnly()
    }

    private var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    private func writeUserChange(_ next: [SongNote]) -> Bool {
        if asyncWrites {
            writer.writeAsync(to: fileURL, notes: next)
            return true
        }
        return writer.writeSync(to: fileURL, notes: next)
    }

    private func persistOnly() {
        // A failed write keeps the user's notes in memory for this run.
        if asyncWrites {
            writer.writeAsync(to: fileURL, notes: stored)
        } else {
            _ = writer.writeSync(to: fileURL, notes: stored)
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

    private nonisolated static let mediumDateFormatter = NotesLockedDateFormatter()

    /// Android `formatNoteDate`: just now / minutes / hours / days ago, then the date.
    nonisolated static func formatDate(_ epochMs: Int64, nowMs: Int64) -> String {
        let delta = max(0, nowMs - epochMs) / 1000
        let minutes = delta / 60, hours = minutes / 60, days = hours / 24
        switch true {
        case minutes < 1: return String(localized: "Just now")
        case minutes < 60: return minutes == 1 ? String(localized: "1 minute ago") : String(localized: "\(minutes) minutes ago")
        case hours < 24: return hours == 1 ? String(localized: "1 hour ago") : String(localized: "\(hours) hours ago")
        case days < 7: return days == 1 ? String(localized: "1 day ago") : String(localized: "\(days) days ago")
        default:
            return mediumDateFormatter.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
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

@MainActor
@Observable
private final class NotesTrackEntry {
    @ObservationIgnored var notes: [SongNote]
    var revision = 0
    init(notes: [SongNote]) { self.notes = notes }
}

@MainActor
@Observable
private final class NotesSignalBox {
    var revision = 0
}

private final class NotesLockedDateFormatter: @unchecked Sendable {
    private let lock = NSLock()
    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = .autoupdatingCurrent
        f.timeZone = .autoupdatingCurrent
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()

    func string(from date: Date) -> String {
        lock.withLock { formatter.string(from: date) }
    }
}

private final class NotesObserverToken: @unchecked Sendable {
    private let token: NSObjectProtocol
    init(_ token: NSObjectProtocol) { self.token = token }
    deinit { NotificationCenter.default.removeObserver(token) }
}

private final class NotesBackgroundTaskHolder: @unchecked Sendable {
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

private final class NotesFileWriter: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.gpo.yoin.notes", qos: .utility)

    private struct PendingJob: Sendable {
        let url: URL
        let notes: [SongNote]
    }

    private struct State: Sendable {
        var pending: PendingJob?
        var scheduled = false
        var activeDrains = 0
    }

    private let state = OSAllocatedUnfairLock<State>(initialState: State())

    func writeAsync(to url: URL, notes: [SongNote]) {
        let schedule = state.withLock { s in
            s.pending = PendingJob(url: url, notes: notes)
            defer { s.scheduled = true }
            return !s.scheduled
        }
        if schedule {
            queue.asyncAfter(deadline: .now() + .milliseconds(50)) { [self] in drain() }
        }
    }

    func writeSync(to url: URL, notes: [SongNote]) -> Bool {
        queue.sync {
            let ok = Self.encodeAndWrite(url: url, notes: notes)
            if ok { state.withLock { $0.pending = nil } }
            return ok
        }
    }

    func flush() { queue.sync { drain() } }

    @MainActor
    func flushInBackground() {
        let hasWork = state.withLock { $0.pending != nil || $0.activeDrains > 0 }
        guard hasWork else { return }
        let holder = NotesBackgroundTaskHolder()
        holder.begin(name: "com.gpo.yoin.notes.flush")
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
        _ = Self.encodeAndWrite(url: job.url, notes: job.notes)
    }

    private static func encodeAndWrite(url: URL, notes: [SongNote]) -> Bool {
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(notes).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
