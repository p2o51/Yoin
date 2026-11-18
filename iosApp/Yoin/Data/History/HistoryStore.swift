import Foundation
import Observation
import os
import UIKit

/// One song that started playing in Yoin. Android `data/local/PlayHistory`
/// (Room `play_history`): recorded once when the song starts playing
/// (`PlaybackManager.recordPlayIfDue`), then marked when it was listened to
/// long enough to scrobble.
struct PlayEvent: Codable, Hashable, Sendable, Identifiable {
    var id: String
    /// The account the play belongs to (Android `profileId`): see
    /// `LibraryStore.historyScope`.
    var scope: String
    var provider: String
    var songId: String
    var albumId: String?
    var artistId: String?
    /// Snapshots, so history reads without the server.
    var title: String
    var album: String
    var artist: String
    /// Subsonic: the cover id; other providers: the artwork URL. Never a URL
    /// with credentials in it (Android `CoverRef.toStorageKey`).
    var coverKey: String?
    /// Epoch ms.
    var startedAt: Int64
    var durationSec: Double
    var listenedSec: Double = 0
    /// Listened to long enough to count as a play (the scrobble rule).
    var completed: Bool = false
}

/// Android `ActivityEntityType` / `ActivityActionType`.
enum ActivityEntity: String, Codable, Sendable { case song, album, artist, playlist }
enum ActivityAction: String, Codable, Sendable { case played, visited }

/// Android `data/local/ActivityEvent` (Room `activity_events`).
struct ActivityRecord: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var scope: String
    var provider: String
    var entity: ActivityEntity
    var action: ActivityAction
    var entityId: String
    var title: String
    var subtitle: String
    var coverKey: String?
    var songId: String?
    var albumId: String?
    var artistId: String?
    /// Epoch ms.
    var timestamp: Int64
}

/// An album's plays in Yoin (Android `AlbumPlayHistoryAggregate`).
struct AlbumPlayStats: Hashable, Sendable {
    var albumId: String
    var playCount: Int
    var firstPlayedAt: Int64
    var lastPlayedAt: Int64
    /// The newest play's snapshot (title / artist / cover) for an album the
    /// app has no other copy of.
    var latest: PlayEvent
}

/// Play history and the activity log, kept on this device as one JSON file
/// in Application Support (`Yoin/history.json`), like the notes and ratings.
/// Every query is scoped to (scope, provider), the Android DAOs' (profileId,
/// provider). `fileURL == nil` keeps everything in memory (tests).
///
/// The file is written off the main thread, a moment after the last change and at once when the app
/// leaves the foreground (Codex #20: rewritten whole on every play and visit, it runs to megabytes).
@MainActor
@Observable
final class HistoryStore {
    static let shared = HistoryStore(fileURL: HistoryStore.defaultURL, now: Date.init, asyncLoad: true)

    /// Each account's oldest rows go first past these (Android keeps everything; a JSON file rewritten
    /// whole wants a ceiling). Counted per account, so one busy account never pushes another's history
    /// out (Codex #15).
    static let maxPlays = 20_000
    static let maxActivities = 4_000

    private(set) var plays: [PlayEvent] = []
    private(set) var activities: [ActivityRecord] = []
    @ObservationIgnored private let fileURL: URL?
    @ObservationIgnored private let writer = HistoryFileWriter()
    @ObservationIgnored let now: () -> Date

    @ObservationIgnored private var accountIndices: [AccountKey: HistoryAccountIndex] = [:]
    @ObservationIgnored private var albumBuckets: [AlbumKey: HistoryAlbumBucket] = [:]
    @ObservationIgnored private var lifecycleObservers: [HistoryObserverToken] = []

    @ObservationIgnored private var startupLoadPending = false
    @ObservationIgnored private var dirtyWhileLoading = false
    @ObservationIgnored private var clearedBeforeLoad: Set<AccountKey> = []
    @ObservationIgnored private let startupLoadGroup = DispatchGroup()
    @ObservationIgnored private let startupResult = OSAllocatedUnfairLock<StartupPayload?>(initialState: nil)

    private struct File: Codable, Sendable {
        var version = 1
        var plays: [PlayEvent]
        var activities: [ActivityRecord]
    }

    private struct AccountKey: Hashable, Sendable {
        var scope: String
        var provider: String
    }

    private struct AlbumKey: Hashable, Sendable {
        var scope: String
        var provider: String
        var albumId: String
    }

    private struct StartupAccountData: Sendable {
        var plays: [PlayEvent]
        var activities: [ActivityRecord]
        var albumStatsById: [String: AlbumPlayStats]
        var albumSongCounts: [String: [String: Int]]
        var songPlayCountsAll: [String: Int]
        var mostRecentPlayBySong: [String: PlayEvent]
    }

    private struct StartupPayload: Sendable {
        var plays: [PlayEvent]
        var activities: [ActivityRecord]
        var accounts: [AccountKey: StartupAccountData]
    }

    convenience init(fileURL: URL?, now: @escaping () -> Date = Date.init) {
        self.init(fileURL: fileURL, now: now, asyncLoad: false)
    }

    private init(fileURL: URL?, now: @escaping () -> Date, asyncLoad: Bool) {
        self.fileURL = fileURL
        self.now = now
        if let fileURL {
            if asyncLoad, Self.shouldDecodeAsync(fileURL) {
                startupLoadPending = true
                startupLoadGroup.enter()
                let startupResult = startupResult
                let startupLoadGroup = startupLoadGroup
                DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                    let loaded = Self.loadFromDisk(fileURL)
                    startupResult.withLock { $0 = loaded }
                    startupLoadGroup.leave()
                    Task { @MainActor [weak self] in
                        self?.finishStartupLoadIfNeeded()
                    }
                }
            } else if let loaded = Self.loadFromDisk(fileURL) {
                installLoaded(loaded)
            }
            // What waits to be written goes out before the app may be suspended.
            for name in [UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
                let token = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    if Thread.isMainThread {
                        MainActor.assumeIsolated { self?.beginBackgroundFlush() }
                    } else {
                        Task { @MainActor [weak self] in self?.beginBackgroundFlush() }
                    }
                }
                lifecycleObservers.append(HistoryObserverToken(token))
            }
        }
    }

    private nonisolated static func shouldDecodeAsync(_ url: URL) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? NSNumber else { return false }
        return size.int64Value >= 65_536
    }

    private nonisolated static func applyPlay(
        _ p: PlayEvent,
        albumStatsById: inout [String: AlbumPlayStats],
        albumSongCounts: inout [String: [String: Int]],
        songPlayCountsAll: inout [String: Int],
        mostRecentPlayBySong: inout [String: PlayEvent]
    ) -> AlbumPlayStats? {
        songPlayCountsAll[p.songId, default: 0] += 1
        if let cur = mostRecentPlayBySong[p.songId] {
            if p.startedAt > cur.startedAt { mostRecentPlayBySong[p.songId] = p }
        } else {
            mostRecentPlayBySong[p.songId] = p
        }
        guard let albumId = p.albumId else { return nil }
        let nextStats: AlbumPlayStats
        if var s = albumStatsById[albumId] {
            s.playCount += 1
            s.firstPlayedAt = min(s.firstPlayedAt, p.startedAt)
            if p.startedAt >= s.lastPlayedAt { s.lastPlayedAt = p.startedAt; s.latest = p }
            nextStats = s
        } else {
            nextStats = AlbumPlayStats(albumId: albumId, playCount: 1, firstPlayedAt: p.startedAt,
                                       lastPlayedAt: p.startedAt, latest: p)
        }
        albumStatsById[albumId] = nextStats
        albumSongCounts[albumId, default: [:]][p.songId, default: 0] += 1
        return nextStats
    }

    private nonisolated static func loadFromDisk(_ fileURL: URL) -> StartupPayload? {
        guard let data = try? Data(contentsOf: fileURL),
              let saved = try? JSONDecoder().decode(File.self, from: data) else { return nil }
        final class Accumulator {
            var plays: [PlayEvent] = []
            var activities: [ActivityRecord] = []
            var albumStatsById: [String: AlbumPlayStats] = [:]
            var albumSongCounts: [String: [String: Int]] = [:]
            var songPlayCountsAll: [String: Int] = [:]
            var mostRecentPlayBySong: [String: PlayEvent] = [:]
        }
        var accumulators: [AccountKey: Accumulator] = [:]
        for p in saved.plays {
            let ak = AccountKey(scope: p.scope, provider: p.provider)
            let acc: Accumulator
            if let existing = accumulators[ak] {
                acc = existing
            } else {
                acc = Accumulator()
                accumulators[ak] = acc
            }
            acc.plays.append(p)
            _ = applyPlay(p, albumStatsById: &acc.albumStatsById, albumSongCounts: &acc.albumSongCounts,
                          songPlayCountsAll: &acc.songPlayCountsAll, mostRecentPlayBySong: &acc.mostRecentPlayBySong)
        }
        for a in saved.activities {
            let ak = AccountKey(scope: a.scope, provider: a.provider)
            let acc: Accumulator
            if let existing = accumulators[ak] {
                acc = existing
            } else {
                acc = Accumulator()
                accumulators[ak] = acc
            }
            acc.activities.append(a)
        }
        let accounts = accumulators.mapValues {
            StartupAccountData(plays: $0.plays, activities: $0.activities, albumStatsById: $0.albumStatsById,
                               albumSongCounts: $0.albumSongCounts, songPlayCountsAll: $0.songPlayCountsAll,
                               mostRecentPlayBySong: $0.mostRecentPlayBySong)
        }
        return StartupPayload(plays: saved.plays, activities: saved.activities, accounts: accounts)
    }

    private func installLoaded(_ payload: StartupPayload) {
        plays = payload.plays
        activities = payload.activities
        for (ak, acc) in payload.accounts {
            let idx = ensureAccountIndex(scope: ak.scope, provider: ak.provider)
            idx.plays = acc.plays
            idx.activities = acc.activities
            idx.albumStatsById = acc.albumStatsById
            idx.albumSongCounts = acc.albumSongCounts
            idx.songPlayCountsAll = acc.songPlayCountsAll
            idx.mostRecentPlayBySong = acc.mostRecentPlayBySong
            idx.sortedRecentPlaysCache = nil
            idx.collapsedRecentActivitiesCache = nil
            idx.lastPlayedAlbumsCache = nil
            idx.playsRevision &+= 1
            idx.activitiesRevision &+= 1
        }
        for (key, bucket) in albumBuckets {
            let idx = accountIndices[AccountKey(scope: key.scope, provider: key.provider)]
            bucket.stats = idx?.albumStatsById[key.albumId]
            bucket.songPlayCounts = idx?.albumSongCounts[key.albumId] ?? [:]
            bucket.statsRevision &+= 1
            bucket.countsRevision &+= 1
        }
    }

    private func finishStartupLoadIfNeeded() {
        guard startupLoadPending else { return }
        startupLoadPending = false
        guard let loaded = startupResult.withLock({ v -> StartupPayload? in
            let out = v
            v = nil
            return out
        }) else {
            if dirtyWhileLoading {
                dirtyWhileLoading = false
                persist()
            }
            return
        }
        if plays.isEmpty && activities.isEmpty && clearedBeforeLoad.isEmpty && !dirtyWhileLoading {
            installLoaded(loaded)
            return
        }
        let cleared = clearedBeforeLoad
        clearedBeforeLoad.removeAll()
        let extraPlays = plays
        let extraActivities = activities
        var mergedPlays = cleared.isEmpty
            ? loaded.plays
            : loaded.plays.filter { !cleared.contains(AccountKey(scope: $0.scope, provider: $0.provider)) }
        mergedPlays.append(contentsOf: extraPlays)
        var mergedActivities = cleared.isEmpty
            ? loaded.activities
            : loaded.activities.filter { !cleared.contains(AccountKey(scope: $0.scope, provider: $0.provider)) }
        mergedActivities.append(contentsOf: extraActivities)

        let touchedAccounts = Set(extraPlays.map { AccountKey(scope: $0.scope, provider: $0.provider) })
            .union(extraActivities.map { AccountKey(scope: $0.scope, provider: $0.provider) })
        for ak in touchedAccounts {
            Self.trim(&mergedPlays, max: Self.maxPlays) { $0.scope == ak.scope && $0.provider == ak.provider }
            Self.trim(&mergedActivities, max: Self.maxActivities) { $0.scope == ak.scope && $0.provider == ak.provider }
        }
        plays = mergedPlays
        activities = mergedActivities
        rebuildAllIndices()
        if dirtyWhileLoading {
            dirtyWhileLoading = false
            persist()
        }
    }

    /// Writes what is waiting now and returns once it is on disk (leaving the foreground; tests).
    func flush() {
        if startupLoadPending {
            startupLoadGroup.wait()
            finishStartupLoadIfNeeded()
        }
        writer.flush()
    }

    private func beginBackgroundFlush() {
        if startupLoadPending {
            guard dirtyWhileLoading else { return }
            let holder = HistoryBackgroundTaskHolder()
            holder.begin(name: "com.gpo.yoin.history.flush")
            let group = startupLoadGroup
            DispatchQueue.global(qos: .utility).async { [weak self] in
                group.wait()
                Task { @MainActor [weak self] in
                    guard let self else {
                        holder.end()
                        return
                    }
                    self.finishStartupLoadIfNeeded()
                    self.writer.flushInBackground(existingTask: holder)
                }
            }
            return
        }
        writer.flushInBackground()
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("history.json")
    }

    var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

    // MARK: Index maintenance

    private func ensureAccountIndex(scope: String, provider: String) -> HistoryAccountIndex {
        let key = AccountKey(scope: scope, provider: provider)
        if let existing = accountIndices[key] { return existing }
        let created = HistoryAccountIndex()
        accountIndices[key] = created
        return created
    }

    private func ensureAlbumBucket(scope: String, provider: String, albumId: String) -> HistoryAlbumBucket {
        let key = AlbumKey(scope: scope, provider: provider, albumId: albumId)
        if let existing = albumBuckets[key] { return existing }
        let idx = accountIndices[AccountKey(scope: scope, provider: provider)]
        let bucket = HistoryAlbumBucket()
        bucket.stats = idx?.albumStatsById[albumId]
        bucket.songPlayCounts = idx?.albumSongCounts[albumId] ?? [:]
        albumBuckets[key] = bucket
        return bucket
    }

    private func appendPlayToIndices(_ play: PlayEvent) {
        let idx = ensureAccountIndex(scope: play.scope, provider: play.provider)
        idx.plays.append(play)
        let nextStats = Self.applyPlay(
            play,
            albumStatsById: &idx.albumStatsById,
            albumSongCounts: &idx.albumSongCounts,
            songPlayCountsAll: &idx.songPlayCountsAll,
            mostRecentPlayBySong: &idx.mostRecentPlayBySong
        )
        if let albumId = play.albumId, let nextStats,
           let bucket = albumBuckets[AlbumKey(scope: play.scope, provider: play.provider, albumId: albumId)] {
            bucket.stats = nextStats
            bucket.songPlayCounts[play.songId, default: 0] += 1
            bucket.statsRevision &+= 1
            bucket.countsRevision &+= 1
        }
        idx.sortedRecentPlaysCache = nil
        idx.lastPlayedAlbumsCache = nil
        idx.playsRevision &+= 1
    }

    private func appendActivityToIndices(_ activity: ActivityRecord) {
        let idx = ensureAccountIndex(scope: activity.scope, provider: activity.provider)
        idx.activities.append(activity)
        idx.collapsedRecentActivitiesCache = nil
        idx.activitiesRevision &+= 1
    }

    private func rebuildPlayAggregates(for key: AccountKey, idx: HistoryAccountIndex) {
        idx.albumStatsById = [:]
        idx.albumSongCounts = [:]
        idx.songPlayCountsAll = [:]
        idx.mostRecentPlayBySong = [:]
        for p in idx.plays {
            _ = Self.applyPlay(
                p,
                albumStatsById: &idx.albumStatsById,
                albumSongCounts: &idx.albumSongCounts,
                songPlayCountsAll: &idx.songPlayCountsAll,
                mostRecentPlayBySong: &idx.mostRecentPlayBySong
            )
        }
        idx.sortedRecentPlaysCache = nil
        idx.lastPlayedAlbumsCache = nil
        idx.playsRevision &+= 1
        for (albumKey, bucket) in albumBuckets where albumKey.scope == key.scope && albumKey.provider == key.provider {
            bucket.stats = idx.albumStatsById[albumKey.albumId]
            bucket.songPlayCounts = idx.albumSongCounts[albumKey.albumId] ?? [:]
            bucket.statsRevision &+= 1
            bucket.countsRevision &+= 1
        }
    }

    private func rebuildAllIndices() {
        for idx in accountIndices.values {
            idx.plays = []
            idx.activities = []
            idx.albumStatsById = [:]
            idx.albumSongCounts = [:]
            idx.songPlayCountsAll = [:]
            idx.mostRecentPlayBySong = [:]
            idx.sortedRecentPlaysCache = nil
            idx.collapsedRecentActivitiesCache = nil
            idx.lastPlayedAlbumsCache = nil
        }
        for p in plays {
            let idx = ensureAccountIndex(scope: p.scope, provider: p.provider)
            idx.plays.append(p)
            _ = Self.applyPlay(
                p,
                albumStatsById: &idx.albumStatsById,
                albumSongCounts: &idx.albumSongCounts,
                songPlayCountsAll: &idx.songPlayCountsAll,
                mostRecentPlayBySong: &idx.mostRecentPlayBySong
            )
        }
        for a in activities {
            let idx = ensureAccountIndex(scope: a.scope, provider: a.provider)
            idx.activities.append(a)
        }
        for idx in accountIndices.values {
            idx.playsRevision &+= 1
            idx.activitiesRevision &+= 1
        }
        for (albumKey, bucket) in albumBuckets {
            let idx = accountIndices[AccountKey(scope: albumKey.scope, provider: albumKey.provider)]
            bucket.stats = idx?.albumStatsById[albumKey.albumId]
            bucket.songPlayCounts = idx?.albumSongCounts[albumKey.albumId] ?? [:]
            bucket.statsRevision &+= 1
            bucket.countsRevision &+= 1
        }
    }

    // MARK: Writing

    /// A song started playing: one play row and one PLAYED activity (the
    /// album's when the song has one, else the song's: Android
    /// `buildPlaybackActivity` with an album / no context). Returns the play's id.
    @discardableResult
    func recordPlay(scope: String, provider: String, songId: String, albumId: String?, artistId: String?,
                    title: String, album: String, artist: String, coverKey: String?, durationSec: Double,
                    at ms: Int64? = nil) -> String {
        let at = ms ?? nowMs
        let album = album.trimmingCharacters(in: .whitespaces)
        let albumId = albumId.flatMap { $0.isEmpty ? nil : $0 }
        let play = PlayEvent(id: UUID().uuidString, scope: scope, provider: provider, songId: songId,
                             albumId: albumId, artistId: artistId, title: title, album: album, artist: artist,
                             coverKey: coverKey, startedAt: at, durationSec: max(0, durationSec))
        plays.append(play)
        appendPlayToIndices(play)
        let activity: ActivityRecord
        if let albumId {
            activity = ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .album,
                                      action: .played, entityId: albumId, title: album, subtitle: artist,
                                      coverKey: coverKey, songId: songId, albumId: albumId, artistId: artistId,
                                      timestamp: at)
        } else {
            activity = ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .song,
                                      action: .played, entityId: songId, title: title, subtitle: artist,
                                      coverKey: coverKey, songId: songId, albumId: nil, artistId: artistId,
                                      timestamp: at)
        }
        activities.append(activity)
        appendActivityToIndices(activity)
        trimAndPersist(scope, provider)
        return play.id
    }

    /// How much of the play was heard; `completed` once it counts as a play.
    func markListened(playId: String, listenedSec: Double, completed: Bool) {
        guard let i = plays.lastIndex(where: { $0.id == playId }) else { return }
        let nextSec = max(plays[i].listenedSec, max(0, listenedSec))
        let nextCompleted = plays[i].completed || completed
        guard nextSec != plays[i].listenedSec || nextCompleted != plays[i].completed else { return }
        plays[i].listenedSec = nextSec
        plays[i].completed = nextCompleted
        let updated = plays[i]
        if let idx = accountIndices[AccountKey(scope: updated.scope, provider: updated.provider)] {
            if let j = idx.plays.lastIndex(where: { $0.id == playId }) {
                idx.plays[j] = updated
            }
            if idx.mostRecentPlayBySong[updated.songId]?.id == playId {
                idx.mostRecentPlayBySong[updated.songId] = updated
            }
            if let albumId = updated.albumId, idx.albumStatsById[albumId]?.latest.id == playId {
                idx.albumStatsById[albumId]?.latest = updated
                if let bucket = albumBuckets[AlbumKey(scope: updated.scope, provider: updated.provider, albumId: albumId)] {
                    bucket.stats?.latest = updated
                    bucket.statsRevision &+= 1
                }
            }
            idx.sortedRecentPlaysCache = nil
            idx.lastPlayedAlbumsCache = nil
            idx.playsRevision &+= 1
        }
        persist()
    }

    /// An album page opened (Android `recordAlbumVisit`).
    func recordAlbumVisit(scope: String, provider: String, albumId: String, title: String, artist: String,
                          artistId: String?, coverKey: String?, at ms: Int64? = nil) {
        guard !albumId.isEmpty else { return }
        let record = ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .album,
                                    action: .visited, entityId: albumId, title: title, subtitle: artist,
                                    coverKey: coverKey, songId: nil, albumId: albumId, artistId: artistId,
                                    timestamp: ms ?? nowMs)
        activities.append(record)
        appendActivityToIndices(record)
        trimAndPersist(scope, provider)
    }

    /// An artist page opened (Android `recordArtistVisit`): the artist's own row, so Activities
    /// can show the artist.
    func recordArtistVisit(scope: String, provider: String, artistId: String, name: String, coverKey: String?,
                           at ms: Int64? = nil) {
        guard !artistId.isEmpty, !name.isEmpty else { return }
        let record = ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .artist,
                                    action: .visited, entityId: artistId, title: name, subtitle: "",
                                    coverKey: coverKey, songId: nil, albumId: nil, artistId: artistId,
                                    timestamp: ms ?? nowMs)
        activities.append(record)
        appendActivityToIndices(record)
        trimAndPersist(scope, provider)
    }

    /// A playlist page opened (Android `recordPlaylistVisit`).
    func recordPlaylistVisit(scope: String, provider: String, playlistId: String, name: String, owner: String,
                             coverKey: String?, at ms: Int64? = nil) {
        guard !playlistId.isEmpty, !name.isEmpty else { return }
        let record = ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .playlist,
                                    action: .visited, entityId: playlistId, title: name, subtitle: owner,
                                    coverKey: coverKey, songId: nil, albumId: nil, artistId: nil,
                                    timestamp: ms ?? nowMs)
        activities.append(record)
        appendActivityToIndices(record)
        trimAndPersist(scope, provider)
    }

    /// Forgets one account's history (debug seeding starts clean with it).
    func removeAll(scope: String, provider: String) {
        let ak = AccountKey(scope: scope, provider: provider)
        if startupLoadPending { clearedBeforeLoad.insert(ak) }
        plays.removeAll { $0.scope == scope && $0.provider == provider }
        activities.removeAll { $0.scope == scope && $0.provider == provider }
        if let idx = accountIndices[ak] {
            idx.plays = []
            idx.activities = []
            idx.albumStatsById = [:]
            idx.albumSongCounts = [:]
            idx.songPlayCountsAll = [:]
            idx.mostRecentPlayBySong = [:]
            idx.sortedRecentPlaysCache = nil
            idx.collapsedRecentActivitiesCache = nil
            idx.lastPlayedAlbumsCache = nil
            idx.playsRevision &+= 1
            idx.activitiesRevision &+= 1
        }
        for (key, bucket) in albumBuckets where key.scope == scope && key.provider == provider {
            bucket.stats = nil
            bucket.songPlayCounts = [:]
            bucket.statsRevision &+= 1
            bucket.countsRevision &+= 1
        }
        persist()
    }

    /// After a row of [scope] / [provider]'s: that account's oldest go past the ceilings, then the file.
    private func trimAndPersist(_ scope: String, _ provider: String) {
        let ak = AccountKey(scope: scope, provider: provider)
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        if idx.plays.count > Self.maxPlays {
            let over = idx.plays.count - Self.maxPlays
            let dropped = over == 1 ? idx.plays.first : nil
            Self.trim(&plays, max: Self.maxPlays) { $0.scope == scope && $0.provider == provider }
            idx.plays.removeFirst(over)
            if let d = dropped,
               idx.mostRecentPlayBySong[d.songId]?.id != d.id,
               !(d.albumId.flatMap { albumId -> Bool in
                   guard let s = idx.albumStatsById[albumId] else { return false }
                   return s.playCount <= 1 || s.firstPlayedAt == d.startedAt || s.latest.id == d.id
               } ?? false) {
                if let c = idx.songPlayCountsAll[d.songId] {
                    if c <= 1 { idx.songPlayCountsAll[d.songId] = nil } else { idx.songPlayCountsAll[d.songId] = c - 1 }
                }
                if let albumId = d.albumId {
                    idx.albumStatsById[albumId]?.playCount -= 1
                    if var counts = idx.albumSongCounts[albumId], let c = counts[d.songId] {
                        if c <= 1 { counts[d.songId] = nil } else { counts[d.songId] = c - 1 }
                        idx.albumSongCounts[albumId] = counts
                    }
                    if let bucket = albumBuckets[AlbumKey(scope: scope, provider: provider, albumId: albumId)] {
                        bucket.stats = idx.albumStatsById[albumId]
                        bucket.songPlayCounts = idx.albumSongCounts[albumId] ?? [:]
                        bucket.statsRevision &+= 1
                        bucket.countsRevision &+= 1
                    }
                }
                idx.sortedRecentPlaysCache = nil
                idx.lastPlayedAlbumsCache = nil
                idx.playsRevision &+= 1
            } else {
                rebuildPlayAggregates(for: ak, idx: idx)
            }
        }
        if idx.activities.count > Self.maxActivities {
            let over = idx.activities.count - Self.maxActivities
            Self.trim(&activities, max: Self.maxActivities) { $0.scope == scope && $0.provider == provider }
            idx.activities.removeFirst(over)
            idx.collapsedRecentActivitiesCache = nil
            idx.activitiesRevision &+= 1
        }
        persist()
    }

    /// The rows [mine] picks past [max] leave [rows], oldest first (rows sit in the order they were
    /// recorded); every other row stays where it is.
    nonisolated static func trim<T>(_ rows: inout [T], max: Int, _ mine: (T) -> Bool) {
        guard rows.count > max else { return }
        var over = rows.reduce(0) { mine($1) ? $0 + 1 : $0 } - max
        guard over > 0 else { return }
        rows = rows.filter { row in
            guard over > 0, mine(row) else { return true }
            over -= 1
            return false
        }
    }

    /// Hands the history as it is now to the writer (copies of the arrays: they share storage until changed).
    private func persist() {
        guard let fileURL else { return }
        if startupLoadPending {
            dirtyWhileLoading = true
            return
        }
        let file = File(plays: plays, activities: activities)
        writer.write(to: fileURL) { try JSONEncoder().encode(file) }
    }

    // MARK: Queries (Android PlayHistoryDao / ActivityEventDao)

    /// One account's plays, oldest first (the order they were recorded).
    func plays(scope: String, provider: String) -> [PlayEvent] {
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.playsRevision
        return idx.plays
    }

    /// Newest first (`getRecentHistory`).
    func recentPlays(scope: String, provider: String, limit: Int = 50) -> [PlayEvent] {
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.playsRevision
        guard limit > 0 else { return [] }
        return Array(idx.sortedRecentPlays().prefix(limit))
    }

    /// The song's newest play (`getMostRecentPlay`).
    func mostRecentPlay(scope: String, provider: String, songId: String) -> PlayEvent? {
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.playsRevision
        return idx.mostRecentPlayBySong[songId]
    }

    /// Every album's plays (`getAlbumAggregatesFor`), keyed by album id.
    func albumStats(scope: String, provider: String) -> [String: AlbumPlayStats] {
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.playsRevision
        return idx.albumStatsById
    }

    func albumStats(scope: String, provider: String, albumId: String) -> AlbumPlayStats? {
        let bucket = ensureAlbumBucket(scope: scope, provider: provider, albumId: albumId)
        _ = bucket.statsRevision
        return bucket.stats
    }

    /// Plays per song (the album page's per-track counts), optionally for one album.
    func songPlayCounts(scope: String, provider: String, albumId: String? = nil) -> [String: Int] {
        if let albumId {
            let bucket = ensureAlbumBucket(scope: scope, provider: provider, albumId: albumId)
            _ = bucket.countsRevision
            return bucket.songPlayCounts
        }
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.playsRevision
        return idx.songPlayCountsAll
    }

    /// Albums by their newest play, newest first.
    func lastPlayedAlbums(scope: String, provider: String, limit: Int = 20) -> [AlbumPlayStats] {
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.playsRevision
        guard limit > 0 else { return [] }
        return Array(idx.lastPlayedAlbumsDesc().prefix(limit))
    }

    /// Albums last played before [before] (epoch ms), longest away first.
    func albumsNotPlayedSince(_ before: Int64, scope: String, provider: String) -> [AlbumPlayStats] {
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.playsRevision
        return idx.lastPlayedAlbumsDesc().reversed().filter { $0.lastPlayedAt < before }
    }

    /// The activity log newest first, one row per (action, entity, id): the
    /// newest (Android `getRecentActivities` → `collapseToLatestUnique`).
    func recentActivities(scope: String, provider: String, limit: Int = 20) -> [ActivityRecord] {
        let idx = ensureAccountIndex(scope: scope, provider: provider)
        _ = idx.activitiesRevision
        guard limit > 0 else { return [] }
        return Array(idx.collapsedRecentActivities().prefix(limit))
    }

    // MARK: Pure helpers

    nonisolated static func albumStats(_ plays: [PlayEvent]) -> [String: AlbumPlayStats] {
        var out: [String: AlbumPlayStats] = [:]
        for p in plays {
            guard let id = p.albumId else { continue }
            if var s = out[id] {
                s.playCount += 1
                s.firstPlayedAt = min(s.firstPlayedAt, p.startedAt)
                if p.startedAt >= s.lastPlayedAt { s.lastPlayedAt = p.startedAt; s.latest = p }
                out[id] = s
            } else {
                out[id] = AlbumPlayStats(albumId: id, playCount: 1, firstPlayedAt: p.startedAt,
                                         lastPlayedAt: p.startedAt, latest: p)
            }
        }
        return out
    }

    /// [newestFirst] keeps its order; later rows of a seen key drop.
    nonisolated static func collapseToLatestUnique<S: Sequence>(_ newestFirst: S) -> [ActivityRecord]
        where S.Element == ActivityRecord {
        var seen = Set<String>()
        var out: [ActivityRecord] = []
        for e in newestFirst where seen.insert("\(e.action):\(e.entity):\(e.entityId):\(e.provider)").inserted {
            out.append(e)
        }
        return out
    }
}

@MainActor
@Observable
private final class HistoryAlbumBucket {
    @ObservationIgnored var stats: AlbumPlayStats?
    @ObservationIgnored var songPlayCounts: [String: Int] = [:]
    var statsRevision = 0
    var countsRevision = 0
}

@MainActor
@Observable
private final class HistoryAccountIndex {
    @ObservationIgnored var plays: [PlayEvent] = []
    @ObservationIgnored var activities: [ActivityRecord] = []
    @ObservationIgnored var albumStatsById: [String: AlbumPlayStats] = [:]
    @ObservationIgnored var albumSongCounts: [String: [String: Int]] = [:]
    @ObservationIgnored var songPlayCountsAll: [String: Int] = [:]
    @ObservationIgnored var mostRecentPlayBySong: [String: PlayEvent] = [:]
    @ObservationIgnored var sortedRecentPlaysCache: (revision: Int, rows: [PlayEvent])?
    @ObservationIgnored var collapsedRecentActivitiesCache: (revision: Int, rows: [ActivityRecord])?
    @ObservationIgnored var lastPlayedAlbumsCache: (revision: Int, desc: [AlbumPlayStats])?
    var playsRevision = 0
    var activitiesRevision = 0

    func sortedRecentPlays() -> [PlayEvent] {
        if let c = sortedRecentPlaysCache, c.revision == playsRevision { return c.rows }
        let sorted = plays.sorted { $0.startedAt > $1.startedAt }
        sortedRecentPlaysCache = (playsRevision, sorted)
        return sorted
    }

    func collapsedRecentActivities() -> [ActivityRecord] {
        if let c = collapsedRecentActivitiesCache, c.revision == activitiesRevision { return c.rows }
        let collapsed = HistoryStore.collapseToLatestUnique(activities.sorted { $0.timestamp > $1.timestamp })
        collapsedRecentActivitiesCache = (activitiesRevision, collapsed)
        return collapsed
    }

    func lastPlayedAlbumsDesc() -> [AlbumPlayStats] {
        if let c = lastPlayedAlbumsCache, c.revision == playsRevision { return c.desc }
        let desc = albumStatsById.values.sorted { $0.lastPlayedAt > $1.lastPlayedAt }
        lastPlayedAlbumsCache = (playsRevision, desc)
        return desc
    }
}

private final class HistoryObserverToken: @unchecked Sendable {
    private let token: NSObjectProtocol
    init(_ token: NSObjectProtocol) { self.token = token }
    deinit { NotificationCenter.default.removeObserver(token) }
}

private final class HistoryBackgroundTaskHolder: @unchecked Sendable {
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

/// The history file's writer: the newest snapshot only, encoded and written on a utility queue a second
/// after the last change, so a burst of plays and visits is one write and none of it holds the main thread.
/// A failed write keeps the history in memory for this run (the next change tries again).
final class HistoryFileWriter: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.gpo.yoin.history", qos: .utility)
    private let delay: DispatchTimeInterval

    private struct PendingJob: Sendable {
        let url: URL
        let encode: @Sendable () throws -> Data
    }

    private struct State: Sendable {
        var pending: PendingJob?
        var scheduled = false
        var activeDrains = 0
    }

    /// Guards [state].
    private let state = OSAllocatedUnfairLock<State>(initialState: State())

    init(delay: DispatchTimeInterval = .seconds(1)) { self.delay = delay }

    func write(to url: URL, _ encode: @escaping @Sendable () throws -> Data) {
        let schedule = state.withLock { s in
            s.pending = PendingJob(url: url, encode: encode)
            defer { s.scheduled = true }
            return !s.scheduled
        }
        if schedule { queue.asyncAfter(deadline: .now() + delay) { [self] in drain() } }
    }

    /// Writes what is waiting, if anything, and returns once it is written.
    func flush() { queue.sync { drain() } }

    /// Schedules an immediate background drain guarded by a UIKit background task.
    @MainActor
    fileprivate func flushInBackground(existingTask: HistoryBackgroundTaskHolder? = nil) {
        let hasWork = state.withLock { $0.pending != nil || $0.activeDrains > 0 }
        guard hasWork else {
            existingTask?.end()
            return
        }
        let holder = existingTask ?? {
            let h = HistoryBackgroundTaskHolder()
            h.begin(name: "com.gpo.yoin.history.flush")
            return h
        }()
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
        do {
            let data = try job.encode()
            try FileManager.default.createDirectory(at: job.url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try data.write(to: job.url, options: .atomic)
        } catch {}
    }
}
