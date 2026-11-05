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
/// check are read again from the account here that follows them — whichever
/// account is in use — saved when they changed, or marked gone when the
/// service no longer lists them. Never in the background.
///
/// The account that follows a backup (`sourceProfileId`, this device's own
/// and never synced) is its profile id (`Profile.id`). Builds before accounts
/// had ids wrote the Subsonic server + user ("baseUrl|username"); those still
/// resolve to the account with that server and user, and move to its id at
/// its next backup. Restores live in PlaylistRestore.swift.
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
    @ObservationIgnored private let injectedAccounts: BackupAccounts?
    @ObservationIgnored private var following: Task<Void, Never>?
    @ObservationIgnored private var observer: NSObjectProtocol?
    @ObservationIgnored private let log = Logger(subsystem: "com.gpo.yoin", category: "backup")

    init(store: PlaylistBackupStore = .shared, accounts: BackupAccounts? = nil,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.store = store
        self.injectedAccounts = accounts
        self.now = now
    }

    /// Every configured account (tests hand their own).
    private var accounts: BackupAccounts { injectedAccounts ?? LiveBackupAccounts.shared }

    /// One account a backup is read from: its provider, its profile id, its name, and how to read it.
    struct Source {
        var provider: String
        var profileId: String
        var label: String
        /// Every playlist the account lists (`provider:rawId`), all of them: a playlist missing here is gone.
        var listPlaylists: @MainActor () async throws -> [String]
        /// One playlist read whole; nil when the service doesn't have it.
        var readPlaylist: @MainActor (_ rawId: String) async throws -> SnapshotPlaylist?
        /// Other `sourceProfileId`s that mean this account (the "baseUrl|username" earlier builds wrote).
        var formerIds: Set<String> = []
        /// Lets go of what reading opened (a client of its own); the source isn't used after.
        var close: @MainActor () -> Void = {}

        /// Whether a backup's `sourceProfileId` names this account.
        func owns(_ sourceProfileId: String?) -> Bool {
            guard let sourceProfileId else { return false }
            return sourceProfileId == profileId || formerIds.contains(sourceProfileId)
        }
    }

    /// The account in use, as a backup source, when it is the one the library shows; nil signed out (the
    /// mock library). Cheap: nothing is opened until it reads.
    func source(_ library: LibraryStore) -> Source? {
        guard let id = accounts.activeId, let profile = accounts.profiles.first(where: { $0.id == id }) else { return nil }
        switch profile.service {
        case .subsonic: guard library.client != nil else { return nil }
        case .appleMusic: guard library.apple != nil else { return nil }
        }
        return accounts.source(id)
    }

    // MARK: Back up

    /// Cloud backup from a playlist page: reads [playlist] whole and saves it; a toast says what came of it,
    /// with View to open the backup (Android PlaylistDetailViewModel.backUp).
    func backUp(_ playlist: PlaylistInfo, library: LibraryStore) {
        let key = playlist.id.rawId
        guard !backingUp.contains(key), let source = source(library) else { return }
        withAnimation(Yoin.effects) { _ = backingUp.insert(key) }
        Task {
            defer {
                source.close()
                withAnimation(Yoin.effects) { _ = backingUp.remove(key) }
            }
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
        let record = existing(of: playlist.id, on: source)
            .map { PlaylistSnapshots.shared.refreshed(backup: $0, playlist: playlist, profileId: source.profileId,
                                                       profileLabel: source.label, now: t) }
            ?? PlaylistSnapshots.shared.backup(id: UUID().uuidString.lowercased(), playlist: playlist,
                                               provider: source.provider, profileId: source.profileId,
                                               profileLabel: source.label, now: t)
        guard store.save(record) else { throw BackupError.notSaved }
        return record
    }

    /// The backup [source] already keeps of [playlistId], if any. Playlist ids are only unique within one
    /// account (two Subsonic servers both have a playlist "1"), so: the account's own backup first; else
    /// one no account here follows — made on another device, whose account the row doesn't name (the
    /// account isn't synced), or whose account was removed here — preferring one made under this
    /// account's name. A backup another account here follows is never taken over.
    func existing(of playlistId: String, on source: Source) -> PlaylistBackupRecord? {
        let rows = store.bySource(provider: source.provider, playlistId: playlistId)
        if let own = rows.first(where: { source.owns($0.sourceProfileId) }) { return own }
        let profiles = accounts.profiles
        let unclaimed = rows.filter { r in
            guard let id = r.sourceProfileId else { return true }
            return Self.owner(of: id, provider: r.sourceProvider, in: profiles) == nil
        }
        return unclaimed.first { $0.sourceProfileLabel == source.label } ?? unclaimed.first { $0.sourceProfileId == nil }
    }

    enum BackupError: Error { case notFound, notSaved, notHere }

    // MARK: Follow

    /// What following one backup found (Android FollowResult).
    enum FollowResult: Equatable { case changed, unchanged, gone, skipped }

    /// From launch: follows the due backups each time the app comes to the foreground, off the return path.
    func start(library: LibraryStore) {
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
            ?? store.bySource(provider: playlist.id.provider, playlistId: "\(playlist.id.provider):\(id)").first?.id
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
        guard following == nil else { return }
        following = Task { [weak self] in
            // Off the return-to-app path: the first frames and the feed come first.
            try? await Task.sleep(for: .seconds(8))
            guard let self, !Task.isCancelled else { self?.following = nil; return }
            let results = await self.followDue()
            if !results.isEmpty { self.log.info("followed \(results.count) backups") }
            self.following = nil
        }
    }

    /// Every backup an account configured here follows that hasn't been checked for [interval], read again
    /// from that account — the one in use or not (Android followDue). An account that can't be read now
    /// (no sign-in, Apple Music access off) leaves its backups for next time.
    func followDue(interval: Int64 = PlaylistBackups.followIntervalMs) async -> [FollowResult] {
        let t = now()
        let profiles = accounts.profiles
        var due: [String: [PlaylistBackupRecord]] = [:]
        var order: [String] = []
        for backup in store.all where backup.following && t - backup.lastBackedUpAt >= interval {
            guard let id = backup.sourceProfileId,
                  let owner = Self.owner(of: id, provider: backup.sourceProvider, in: profiles) else { continue }
            if due[owner.id] == nil { order.append(owner.id) }
            due[owner.id, default: []].append(backup)
        }
        var out: [FollowResult] = []
        for profileId in order {
            let backups = due[profileId] ?? []
            guard let source = accounts.source(profileId) else {
                out += backups.map { _ in .skipped }
                continue
            }
            out += await followDue(backups, on: source)
            source.close()
        }
        return out
    }

    /// [backups], all followed by [source]'s account, read again from it.
    private func followDue(_ backups: [PlaylistBackupRecord], on source: Source) async -> [FollowResult] {
        // Gone = the service answers with its whole list and the playlist isn't in it; a failed answer proves nothing.
        guard let listed = try? await source.listPlaylists() else { return backups.map { _ in .skipped } }
        var out: [FollowResult] = []
        for backup in backups { out.append(await follow(backup, source: source, listed: Set(listed))) }
        return out
    }

    /// The backup page's Back up now: read again now from this device's account that follows it, whichever
    /// account is in use.
    func followNow(_ backup: PlaylistBackupRecord) async throws -> FollowResult {
        guard let id = backup.sourceProfileId,
              let owner = Self.owner(of: id, provider: backup.sourceProvider, in: accounts.profiles),
              let source = accounts.source(owner.id) else { throw BackupError.notHere }
        defer { source.close() }
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

    /// Whether an account configured here follows [backup] and can read it now (its page offers Back up now).
    func followsHere(_ backup: PlaylistBackupRecord) -> Bool {
        guard let id = backup.sourceProfileId,
              let owner = Self.owner(of: id, provider: backup.sourceProvider, in: accounts.profiles) else { return false }
        return accounts.canRead(owner.id)
    }

    // MARK: Accounts

    /// The account among [profiles] a backup's `sourceProfileId` names: its profile id, or (written before
    /// accounts had ids) its Subsonic server + user.
    static func owner(of sourceProfileId: String, provider: String, in profiles: [Profile]) -> Profile? {
        profiles.first { p in
            Self.provider(of: p) == provider && (p.id == sourceProfileId || formerIds(of: p).contains(sourceProfileId))
        }
    }

    static func provider(of p: Profile) -> String {
        switch p.service {
        case .subsonic: AccountScope.subsonicProvider
        case .appleMusic: AppleMusicMapping.provider
        }
    }

    /// The keys backups written before accounts had ids carry for a Subsonic account: its scope
    /// ("baseUrl|username" as first saved, `Profile.subsonicScope`) and its address + user as now written.
    static func formerIds(of p: Profile) -> Set<String> {
        guard p.service == .subsonic, let baseUrl = p.baseUrl, let username = p.username else { return [] }
        return Set(["\(baseUrl)|\(username)", p.subsonicScope].compactMap { $0 })
    }

    // MARK: Sources

    /// [profile]'s Subsonic server as a backup source, on a client of its own opened at the first read
    /// (never the account in use's: a switch closes that one).
    static func subsonicSource(_ profile: Profile, account: ServerSession.Account) -> Source {
        let client = LazySubsonicClient(account)
        return Source(provider: AccountScope.subsonicProvider, profileId: profile.id, label: profile.title,
                      listPlaylists: { try await client.get().getPlaylists().map { "\($0.ref.provider):\($0.id)" } },
                      readPlaylist: { rawId in try await client.get().getPlaylist(id: rawId).map(snapshot) },
                      formerIds: formerIds(of: profile), close: { client.close() })
    }

    static func appleSource(_ apple: AppleMusicSource, profileId: String = ProfileStore.appleMusicId) -> Source {
        Source(provider: AppleMusicMapping.provider, profileId: profileId, label: "Apple Music",
               listPlaylists: { try await apple.libraryPlaylists().map { "\($0.id.provider):\($0.id.rawId)" } },
               readPlaylist: { rawId in try await apple.playlistForBackup(rawId).map(snapshot) })
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

    /// An Apple Music playlist read whole as a backup reads it (Android `readPlaylist`): its songs in
    /// order, repeats kept, and each entry that isn't a song kept as unreadable at its place among all
    /// the entries.
    nonisolated static func snapshot(_ read: AMPlaylistRead) -> SnapshotPlaylist {
        var tracks: [SnapshotTrack] = []
        var unreadable: [SnapshotUnreadable] = []
        for (i, entry) in read.entries.enumerated() {
            switch entry {
            case .song(let s):
                let seconds = (s.durationMs ?? 0) / 1000
                tracks.append(SnapshotTrack(id: "\(AppleMusicMapping.provider):\(s.rawId)",
                                            title: s.title.isEmpty ? nil : s.title,
                                            artists: s.artist.isEmpty ? [] : [s.artist],
                                            album: s.album.isEmpty ? nil : s.album,
                                            durationSec: seconds > 0 ? KotlinInt(int: Int32(seconds)) : nil, isrc: nil))
            case .other(let title, let artist, let video):
                unreadable.append(SnapshotUnreadable(position: Int32(i), title: title, artist: artist,
                                                     reason: video ? .video : .unavailable))
            }
        }
        let p = read.playlist
        return SnapshotPlaylist(id: "\(AppleMusicMapping.provider):\(p.rawId)", name: p.name, comment: p.description,
                                createdAt: nil, tracks: tracks, unreadable: unreadable)
    }
}

/// The accounts backups read from and restore into (Android BackupProfiles): every account configured
/// here, each opened on its own, so following and restoring never depend on — or change — the account in use.
@MainActor
protocol BackupAccounts: AnyObject {
    var profiles: [Profile] { get }
    var activeId: String? { get }
    /// Whether [profileId] can be read now (a Subsonic sign-in to use; Apple Music access on).
    func canRead(_ profileId: String) -> Bool
    /// [profileId] as a backup source; nil when it is gone or can't be read now.
    func source(_ profileId: String) -> PlaylistBackups.Source?
    /// [profileId] as a restore's destination; nil when it is gone or can't take a playlist now.
    func destination(_ profileId: String) -> RestoreDestination?
}

/// The app's accounts (ProfileManager) and this device's Apple Music.
@MainActor
final class LiveBackupAccounts: BackupAccounts {
    static let shared = LiveBackupAccounts()

    private var manager: ProfileManager { AppModel.shared.profiles }
    private var apple: AppleMusicSource { AppModel.shared.store.appleMusic }

    var profiles: [Profile] { manager.profiles }
    var activeId: String? { manager.activeId }

    func canRead(_ profileId: String) -> Bool {
        guard let p = manager.profile(profileId) else { return false }
        switch p.service {
        case .subsonic: return manager.signIn(p.id) != nil
        case .appleMusic: return apple.isAuthorized
        }
    }

    func source(_ profileId: String) -> PlaylistBackups.Source? {
        guard let p = manager.profile(profileId), canRead(profileId) else { return nil }
        switch p.service {
        case .subsonic: return manager.signIn(p.id).map { PlaylistBackups.subsonicSource(p, account: $0) }
        case .appleMusic: return PlaylistBackups.appleSource(apple, profileId: p.id)
        }
    }

    func destination(_ profileId: String) -> RestoreDestination? {
        guard let p = manager.profile(profileId), canRead(profileId) else { return nil }
        switch p.service {
        case .subsonic: return manager.signIn(p.id).map { SubsonicRestoreDestination(account: $0) }
        case .appleMusic: return AppleMusicRestoreDestination(apple: apple)
        }
    }
}

/// A Subsonic client of a backup's or a restore's own, opened at its first use and closed with it.
@MainActor
final class LazySubsonicClient {
    private let account: ServerSession.Account
    private var client: SubsonicClient?

    init(_ account: ServerSession.Account) { self.account = account }

    func get() -> SubsonicClient {
        if let client { return client }
        let made = SubsonicClient(baseUrl: account.baseUrl, username: account.username, password: account.password)
        client = made
        return made
    }

    func close() {
        client?.close()
        client = nil
    }
}

// MARK: - Apple Music, as backups and restores read and write it

/// A playlist read whole for a backup: every entry in order, repeats kept (Android `readPlaylist`).
struct AMPlaylistRead: Sendable {
    enum Entry: Hashable, Sendable {
        case song(AMSong)
        /// Not a song (a music video) or not readable as one: kept at its place, unbacked-up.
        case other(title: String?, artist: String?, video: Bool)
    }

    var playlist: AMPlaylist
    var entries: [Entry]
}

extension AppleMusicMapping {
    /// [entries] (a playlist's tracks pages, in order) as a backup reads them (Android `songTracks` +
    /// `unreadableEntries`).
    static func playlistRead(_ p: AMPlaylist, entries: [AppleMusicJSON.Object]) -> AMPlaylistRead {
        AMPlaylistRead(playlist: p, entries: entries.map { e in
            if let s = song(e) { return .song(s) }
            let a = AppleMusicJSON.attributes(e)
            return .other(title: AppleMusicJSON.string(a, "name"), artist: AppleMusicJSON.string(a, "artistName"),
                          video: (e["type"] as? String)?.contains("music-videos") == true)
        })
    }

    /// A new library playlist's attributes (Android `createLibraryPlaylist`).
    static func playlistAttributes(name: String, description: String?) -> [String: Any] {
        var a: [String: Any] = ["name": name]
        if let description = description?.trimmingCharacters(in: .whitespacesAndNewlines), !description.isEmpty {
            a["description"] = description
        }
        return a
    }

    /// An `applemusic:` song id as a playlist write's track reference (Android `trackRef`): a catalog song by
    /// its digits, a library song (`library:i.…`) as a library song.
    static func trackRef(_ id: String) throws -> [String: String] {
        let raw = id.hasPrefix(provider + ":") ? String(id.dropFirst(provider.count + 1)) : nil
        if let raw, isCatalogSongId(raw) { return ["id": raw, "type": "songs"] }
        if let raw, isLibraryId(raw), !resourceId(raw).isEmpty { return ["id": resourceId(raw), "type": "library-songs"] }
        throw AppleMusicSource.RequestError(message: "An Apple Music song is required")
    }
}
