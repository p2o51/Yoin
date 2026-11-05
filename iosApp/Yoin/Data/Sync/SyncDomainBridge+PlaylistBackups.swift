import Foundation
import YoinShared

/// `playlist_backup` (shared PlaylistBackupSyncAdapter) through
/// PlaylistBackupStore. Global, not per account: every device holds every
/// backup. A row arrives with its JSON text and is kept as received; the
/// adapter carries this device's `sourceProfileId` over from the local row.
///
/// Kept apart from SyncDomainBridge.swift so the account rework there and
/// this kind don't touch one file. Kotlin calls in on the main thread.
extension SyncDomainBridge {
    private func onMain<T>(_ body: @MainActor () -> T) -> T {
        nonisolated(unsafe) var out: T?
        MainActor.assumeIsolated { out = body() }
        return out!
    }

    func playlistBackups() -> [PlaylistBackupRow] {
        onMain { PlaylistBackupStore.shared.syncedRows }
    }

    func playlistBackup(id: String) -> PlaylistBackupRow? {
        onMain { PlaylistBackupStore.shared.syncedRow(id) }
    }

    func putPlaylistBackup(row: PlaylistBackupRow) -> Bool {
        onMain { PlaylistBackupStore.shared.applySynced(row) }
    }

    func deletePlaylistBackup(id: String) -> Bool {
        onMain { PlaylistBackupStore.shared.removeSynced(id) }
    }
}
