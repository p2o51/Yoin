import Foundation
import Observation
import SwiftUI
import UIKit
import YoinShared
import os

/// Playlist backup (Android PlaylistBackupService's back up + follow,
/// docs/playlist-transfer.md §7): a playlist page's ⋮ › Cloud backup reads the
/// playlist whole, afresh, and saves it as a backup (or backs an existing one
/// up again); each time the app comes to the foreground, the backups due a
/// check are read again from the account here that follows them, saved when
/// they changed, or marked gone when the service no longer lists them. Never
/// in the background.
///
/// The account that follows a backup (`sourceProfileId`, this device's own
/// and never synced) is the Subsonic server + user (`AccountScope`'s key,
/// "baseUrl|username") or `ios-applemusic`. Restores live in
/// PlaylistRestore.swift.
@MainActor
@Observable
final class PlaylistBackups {
    static let shared = PlaylistBackups()

    /// How often a followed playlist is read again (Android FOLLOW_INTERVAL_MS).
    static let followIntervalMs: Int64 = 6 * 60 * 60 * 1000

    /// The playlist page whose Cloud backup asked to open its backup (the toast's View).
    struct ViewRequest: Hashable, Identifiable {
        var backupId: String
        var playlistId: String
        var id: String { backupId }
    }

    /// Playlists being read for a backup now (the menu row waits).
    private(set) var backingUp: Set<String> = []
    var viewRequest: ViewRequest?

    @ObservationIgnored private let store: PlaylistBackupStore
    @ObservationIgnored private let now: () -> Int64
    @ObservationIgnored private weak var library: LibraryStore?
    @ObservationIgnored private var following: Task<Void, Never>?
    @ObservationIgnored private var observer: NSObjectProtocol?
    @ObservationIgnored private let log = Logger(subsystem: "com.gpo.yoin", category: "backup")

    init(store: PlaylistBackupStore = .shared, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.store = store
        self.now = now
    }

    /// The account a backup is read from: its provider, this device's key for it, its name, and how to read.
    struct Source {
        var provider: String
        var profileId: String
        var label: String
        /// Every playlist the account lists (`provider:rawId`), to tell a playlist gone from a failed answer.
        var listPlaylists: @MainActor () async throws -> [String]
        /// One playlist read whole; nil when the service doesn't have it.
        var readPlaylist: @MainActor (_ rawId: String) async throws -> SnapshotPlaylist?
    }

    /// The account in use, as a backup source; nil signed out (the mock library).
    func source(_ library: LibraryStore) -> Source? {
        if let client = library.client, let scope = AccountScope.shared.scope(for: AccountScope.subsonicProvider) {
            return Self.subsonicSource(client, profileId: scope, label: library.session.account?.username ?? "Subsonic")
        }
        if let apple = library.apple { return Self.appleSource(apple) }
        return nil
    }

    // MARK: Back up

    /// Cloud backup from a playlist page: reads [playlist] whole and saves it; a toast says what came of it,
    /// with View to open the backup (Android PlaylistDetailViewModel.backUp).
    func backUp(_ playlist: PlaylistInfo, library: LibraryStore) {
        let key = playlist.id.rawId
        guard !backingUp.contains(key), let source = source(library) else { return }
        withAnimation(Yoin.effects) { _ = backingUp.insert(key) }
        Task {
            defer { withAnimation(Yoin.effects) { _ = backingUp.remove(key) } }
            do {
                let record = try await backUp(rawId: key, from: source)
                let total = Int(record.trackCount + record.unexportedCount)
                let songs = Int(record.trackCount)
                let text = record.unexportedCount == 0
                    ? "Backed up \(songs) \(songs == 1 ? "song" : "songs")"
                    : "Backed up \(songs) of \(total) \(total == 1 ? "song" : "songs")"
                PlaylistActions.shared.show(text, action: .init(label: "View") { [weak self] in
                    self?.viewRequest = ViewRequest(backupId: record.id, playlistId: key)
                })
            } catch {
                log.error("backup failed: \(error.localizedDescription, privacy: .public)")
                PlaylistActions.shared.show("Couldn't back up the playlist")
            }
        }
    }

    /// Starts [rawId]'s backup on [source], or — when it already has one — backs it up again now.
    func backUp(rawId: String, from source: Source) async throws -> PlaylistBackupRecord {
        guard let playlist = try await source.readPlaylist(rawId) else { throw BackupError.notFound }
        let t = now()
        let record = store.findBySource(provider: source.provider, playlistId: playlist.id)
            .map { PlaylistSnapshots.shared.refreshed(backup: $0, playlist: playlist, profileId: source.profileId,
                                                       profileLabel: source.label, now: t) }
            ?? PlaylistSnapshots.shared.backup(id: UUID().uuidString.lowercased(), playlist: playlist,
                                               provider: source.provider, profileId: source.profileId,
                                               profileLabel: source.label, now: t)
        guard store.save(record) else { throw BackupError.notSaved }
        return record
    }

    enum BackupError: Error { case notFound, notSaved, notHere }

    // MARK: Follow

    /// What following one backup found (Android FollowResult).
    enum FollowResult: Equatable { case changed, unchanged, gone, skipped }

    /// From launch: follows the due backups each time the app comes to the foreground, off the return path.
    func start(library: LibraryStore) {
        self.library = library
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
                                                          object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { PlaylistBackups.shared.foreground() }
        }
        #if DEBUG
        seedDemoBackup()
        Task { await runDemo(library) }
        #endif
        foreground()
    }

    #if DEBUG
    /// `-backupSeed YES`: a backup made on another device from a Spotify playlist (as Android syncs one),
    /// for QA of a restore against the demo server: six songs it has, a live take of one it has the
    /// studio cut of (to check), and two it doesn't have (not found).
    /// `-backupDemo backup|open|restore|review|create` (with `-serverRoute playlist:<id>`): once the playlist
    /// page has loaded, Cloud backup from its menu; `open` and later also open a backup's page (the one just
    /// made, or `-backupOpenId <id>`), where PlaylistBackupView carries the restore on. For QA without touch.
    private func runDemo(_ library: LibraryStore) async {
        let d = UserDefaults.standard
        guard let demo = d.string(forKey: "backupDemo"), let route = d.string(forKey: "serverRoute"),
              route.hasPrefix("playlist:") else { return }
        let id = String(route.dropFirst(9))
        for _ in 0..<80 where library.playlist(id) == nil { try? await Task.sleep(for: .milliseconds(250)) }
        try? await Task.sleep(for: .seconds(1.5))
        guard let playlist = library.playlist(id) else { return log.error("demo: no playlist") }
        backUp(playlist, library: library)
        guard demo != "backup" else { return }
        try? await Task.sleep(for: .seconds(2.5))
        let backupId = d.string(forKey: "backupOpenId")
            ?? store.findBySource(provider: playlist.id.provider, playlistId: "\(playlist.id.provider):\(id)")?.id
        if let backupId { viewRequest = ViewRequest(backupId: backupId, playlistId: id) }
    }

    private func seedDemoBackup() {
        guard UserDefaults.standard.bool(forKey: "backupSeed"), store.record("qa-seed") == nil else { return }
        func t(_ title: String, _ artist: String, _ album: String, _ id: String) -> SnapshotTrack {
            SnapshotTrack(id: "spotify:\(id)", title: title, artists: [artist], album: album, durationSec: nil, isrc: nil)
        }
        let playlist = SnapshotPlaylist(
            id: "spotify:37i9qa", name: "Night drive", comment: "Windows down", createdAt: "2025-07-12T21:04:00Z",
            tracks: [t("Neon Saints", "The Meridians", "Glass Orchard", "1"), t("Midnight Radio", "Sora Hayama", "午後のソーダ", "2"),
                     t("Slow Dance in a Fast Car (Live)", "The Meridians", "Velvet Hours Live", "3"),
                     t("Creep", "Radiohead", "Pablo Honey", "4"), t("Afterglow Avenue", "Kiri Ono", "Harbor Lights", "5"),
                     t("夜航", "林间", "夜航", "6"), t("夜に駆ける", "YOASOBI", "THE BOOK", "7"),
                     t("Rooftops", "The Meridians", "Velvet Hours", "8"), t("Undertow", "Kiri Ono", "Low Tide Static", "9")],
            unreadable: [SnapshotUnreadable(position: 9, title: "Voice memo", artist: nil, reason: .localfile)])
        let t0 = now() - 3 * 24 * 60 * 60 * 1000
        store.save(PlaylistSnapshots.shared.backup(id: "qa-seed", playlist: playlist, provider: "spotify", profileId: nil,
                                                   profileLabel: "Chen", now: t0))
    }
    #endif

    private func foreground() {
        guard following == nil, let library else { return }
        following = Task { [weak self] in
            // Off the return-to-app path: the first frames and the feed come first.
            try? await Task.sleep(for: .seconds(8))
            guard let self, !Task.isCancelled, let source = self.source(library) else { self?.following = nil; return }
            let results = await self.followDue(source)
            if !results.isEmpty { self.log.info("followed \(results.count) backups") }
            self.following = nil
        }
    }

    /// Every backup [source]'s account follows that hasn't been checked for [interval], read again.
    func followDue(_ source: Source, interval: Int64 = PlaylistBackups.followIntervalMs) async -> [FollowResult] {
        let t = now()
        let due = store.all.filter {
            $0.following && $0.sourceProfileId == source.profileId && $0.sourceProvider == source.provider
                && t - $0.lastBackedUpAt >= interval
        }
        guard !due.isEmpty else { return [] }
        // Gone = the service answers with its list and the playlist isn't in it; a failed answer proves nothing.
        guard let listed = try? await source.listPlaylists() else { return due.map { _ in .skipped } }
        var out: [FollowResult] = []
        for backup in due { out.append(await follow(backup, source: source, listed: Set(listed))) }
        return out
    }

    /// The backup page's Back up now: read again now from this device's account that follows it.
    func followNow(_ backup: PlaylistBackupRecord, library: LibraryStore) async throws -> FollowResult {
        guard let source = source(library), backup.sourceProfileId == source.profileId,
              backup.sourceProvider == source.provider else { throw BackupError.notHere }
        let listed = try await source.listPlaylists()
        return await follow(backup, source: source, listed: Set(listed))
    }

    func follow(_ backup: PlaylistBackupRecord, source: Source, listed: Set<String>) async -> FollowResult {
        guard let rawId = PlaylistSnapshots.shared.rawIdOf(id: backup.sourcePlaylistId) else { return .skipped }
        if !listed.contains(backup.sourcePlaylistId) {
            store.save(backup.markedGone(now: now()))
            return .gone
        }
        guard let playlist = try? await source.readPlaylist(rawId) else { return .skipped }
        // Re-read the row: a sync or a restore may have changed it while the playlist loaded.
        let current = store.record(backup.id) ?? backup
        let t = now()
        let next = PlaylistSnapshots.shared.refreshed(backup: current, playlist: playlist, profileId: source.profileId,
                                                      profileLabel: source.label, now: t)
        store.save(next)
        return next.lastChangedAt == t ? .changed : .unchanged
    }

    /// Whether this device's account in use follows [backup] (its page offers Back up now).
    func followsHere(_ backup: PlaylistBackupRecord, library: LibraryStore) -> Bool {
        guard let source = source(library) else { return false }
        return backup.sourceProfileId == source.profileId && backup.sourceProvider == source.provider
    }

    // MARK: Sources

    static func subsonicSource(_ client: SubsonicClient, profileId: String, label: String) -> Source {
        Source(provider: AccountScope.subsonicProvider, profileId: profileId, label: label,
               listPlaylists: { try await client.getPlaylists().map { "\($0.ref.provider):\($0.id)" } },
               readPlaylist: { rawId in try await client.getPlaylist(id: rawId).map(snapshot) })
    }

    static func appleSource(_ apple: AppleMusicSource) -> Source {
        Source(provider: AppleMusicMapping.provider, profileId: SyncDomainBridge.appleMusicProfileId, label: "Apple Music",
               listPlaylists: { try await apple.libraryPlaylists().map { "\($0.id.provider):\($0.id.rawId)" } },
               readPlaylist: { rawId in try await apple.playlist(rawId).map(snapshot) })
    }

    /// A Subsonic playlist as a backup reads it: each song's title, artist, album, length and id.
    nonisolated static func snapshot(_ d: PlaylistDetail) -> SnapshotPlaylist {
        let p = d.summary
        let tracks = d.entries.map { s in
            SnapshotTrack(id: "\(s.ref.provider):\(s.id)", title: s.title,
                          artists: [s.artist].compactMap { $0 }.filter { !$0.isEmpty },
                          album: s.album, durationSec: s.durationSec, isrc: nil)
        }
        return SnapshotPlaylist(id: "\(p.ref.provider):\(p.id)", name: p.name, comment: p.comment, createdAt: p.created,
                                tracks: tracks, unreadable: [])
    }

    /// Any provider's playlist page model (Apple Music) as a backup reads it.
    nonisolated static func snapshot(_ p: PlaylistInfo) -> SnapshotPlaylist {
        let tracks = p.entries.map { e in
            let artist = e.track.artist ?? e.album.artist
            return SnapshotTrack(id: "\(e.track.id.provider):\(e.track.id.rawId)", title: e.track.title,
                                 artists: artist.isEmpty ? [] : [artist], album: e.album.title,
                                 durationSec: e.track.durationSec > 0 ? KotlinInt(int: Int32(e.track.durationSec)) : nil,
                                 isrc: nil)
        }
        return SnapshotPlaylist(id: "\(p.id.provider):\(p.id.rawId)", name: p.title, comment: p.description,
                                createdAt: nil, tracks: tracks, unreadable: [])
    }
}
