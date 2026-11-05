import Foundation
import Observation
import YoinShared

// Immutable Kotlin data classes (shared backup + transfer models).
extension PlaylistBackupRow: @retroactive @unchecked Sendable {}
extension PlaylistBackupRecord: @retroactive @unchecked Sendable {}
extension PlaylistRestoreRecord: @retroactive @unchecked Sendable {}
extension MatchTrack: @retroactive @unchecked Sendable {}
extension ReviewRow: @retroactive @unchecked Sendable {}

/// Playlist cloud backups on this device (Android `playlist_backups` +
/// PlaylistBackupStore, docs/playlist-transfer.md §7): one row per playlist,
/// kept up to date while the playlist is there, kept after it is gone, to be
/// restored into any account later. Global, not per account; synced as
/// `playlist_backup` (shared PlaylistBackupSyncAdapter).
///
/// A row is the shared `PlaylistBackupRow`: the list columns, and the
/// snapshot, its versions and restore history as the exact JSON text written
/// or received, so another device's extra fields survive and every device
/// hashes the row alike. Kept in one JSON file; every write goes through here
/// and tells cloud sync (`onLocalChange`), except sync's own.
@MainActor
@Observable
final class PlaylistBackupStore {
    static let shared = PlaylistBackupStore(fileURL: PlaylistBackupStore.defaultURL)

    private var rows: [String: PlaylistBackupRow] = [:]
    /// The rows this build can read, decoded once per change.
    private var records: [String: PlaylistBackupRecord] = [:]
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private let now: () -> Int64
    /// A local backup, restore or delete: cloud sync has something to send.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    init(fileURL: URL, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.fileURL = fileURL
        self.now = now
        guard let data = try? Data(contentsOf: fileURL),
              let list = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return }
        for o in list {
            guard let row = Self.row(o) else { continue }
            rows[row.id] = row
            records[row.id] = PlaylistBackupCodec.shared.fromRow(row: row)
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("playlist-backups.json")
    }

    // MARK: Reading

    /// Every backup, latest change first (Android `observeAll`); rows this build can't read are left out.
    var all: [PlaylistBackupRecord] {
        records.values.sorted { ($0.lastChangedAt, $1.id) > ($1.lastChangedAt, $0.id) }
    }

    func record(_ id: String) -> PlaylistBackupRecord? { records[id] }

    /// Every backup of [playlistId] (`provider:rawId`) on [provider], oldest first. More than one when two
    /// accounts of the service each have a playlist with that id (two Subsonic servers' playlist "1"), or a
    /// sync raced: which one an account keeps is PlaylistBackups.existing's call.
    func bySource(provider: String, playlistId: String) -> [PlaylistBackupRecord] {
        records.values
            .filter { $0.sourceProvider == provider && $0.sourcePlaylistId == playlistId }
            .sorted { ($0.firstBackedUpAt, $0.id) < ($1.firstBackedUpAt, $1.id) }
    }

    // MARK: Writes

    /// Saves [record] (a backup, a follow-up, the playlist found gone); false when the file couldn't be written.
    @discardableResult
    func save(_ record: PlaylistBackupRecord) -> Bool {
        guard put(PlaylistBackupCodec.shared.toRow(record: record)) else { return false }
        Self.onLocalChange?()
        return true
    }

    @discardableResult
    func delete(_ id: String) -> Bool {
        guard remove(id) else { return false }
        Self.onLocalChange?()
        return true
    }

    /// [restore] added to the end of backup [id]'s history; false when the backup is gone.
    @discardableResult
    func recordRestore(_ id: String, _ restore: PlaylistRestoreRecord) -> Bool {
        guard let row = rows[id] else { return false }
        guard put(PlaylistBackupCodec.shared.withRestore(row: row, record: restore, now: now())) else { return false }
        Self.onLocalChange?()
        return true
    }

    // MARK: Cloud sync (SyncDomainBridge+PlaylistBackups)

    var syncedRows: [PlaylistBackupRow] { rows.values.sorted { $0.id < $1.id } }

    func syncedRow(_ id: String) -> PlaylistBackupRow? { rows[id] }

    /// Another device's version of a backup, as received (its JSON text kept). False when not on disk.
    func applySynced(_ row: PlaylistBackupRow) -> Bool { put(row) }

    func removeSynced(_ id: String) -> Bool { rows[id] == nil || remove(id) }

    // MARK: File

    private func put(_ row: PlaylistBackupRow) -> Bool {
        let old = rows[row.id], oldRecord = records[row.id]
        rows[row.id] = row
        records[row.id] = PlaylistBackupCodec.shared.fromRow(row: row)
        guard write() else {
            rows[row.id] = old
            records[row.id] = oldRecord
            return false
        }
        return true
    }

    private func remove(_ id: String) -> Bool {
        guard let old = rows[id] else { return true }
        let oldRecord = records[id]
        rows[id] = nil
        records[id] = nil
        guard write() else {
            rows[id] = old
            records[id] = oldRecord
            return false
        }
        return true
    }

    /// True once the file holds every row.
    private func write() -> Bool {
        let list = syncedRows.map(Self.object)
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONSerialization.data(withJSONObject: list, options: [.sortedKeys])
            try data.write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    private static func object(_ r: PlaylistBackupRow) -> [String: Any] {
        var o: [String: Any] = [
            "id": r.id, "name": r.name, "sourceProvider": r.sourceProvider, "sourceProfileLabel": r.sourceProfileLabel,
            "sourcePlaylistId": r.sourcePlaylistId, "firstBackedUpAt": NSNumber(value: r.firstBackedUpAt),
            "lastBackedUpAt": NSNumber(value: r.lastBackedUpAt), "lastChangedAt": NSNumber(value: r.lastChangedAt),
            "contentJson": r.contentJson, "versionsJson": r.versionsJson, "restoresJson": r.restoresJson,
            "updatedAt": NSNumber(value: r.updatedAt),
        ]
        o["sourceProfileId"] = r.sourceProfileId
        o["playlistCreatedAt"] = r.playlistCreatedAt
        o["sourceGoneAt"] = r.sourceGoneAt.map { NSNumber(value: $0.int64Value) }
        return o
    }

    private static func row(_ o: [String: Any]) -> PlaylistBackupRow? {
        func long(_ k: String) -> Int64? { (o[k] as? NSNumber)?.int64Value }
        guard let id = o["id"] as? String, let name = o["name"] as? String,
              let provider = o["sourceProvider"] as? String, let label = o["sourceProfileLabel"] as? String,
              let playlistId = o["sourcePlaylistId"] as? String, let first = long("firstBackedUpAt"),
              let last = long("lastBackedUpAt"), let changed = long("lastChangedAt"),
              let content = o["contentJson"] as? String, let versions = o["versionsJson"] as? String,
              let restores = o["restoresJson"] as? String, let updated = long("updatedAt") else { return nil }
        return PlaylistBackupRow(id: id, name: name, sourceProvider: provider, sourceProfileLabel: label,
                                 sourcePlaylistId: playlistId, sourceProfileId: o["sourceProfileId"] as? String,
                                 playlistCreatedAt: o["playlistCreatedAt"] as? String, firstBackedUpAt: first,
                                 lastBackedUpAt: last, lastChangedAt: changed,
                                 sourceGoneAt: long("sourceGoneAt").map { KotlinLong(value: $0) },
                                 contentJson: content, versionsJson: versions, restoresJson: restores,
                                 updatedAt: updated)
    }
}
