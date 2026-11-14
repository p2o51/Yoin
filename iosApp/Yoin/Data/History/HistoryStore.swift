import Foundation
import Observation
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
    static let shared = HistoryStore(fileURL: HistoryStore.defaultURL)

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

    private struct File: Codable {
        var version = 1
        var plays: [PlayEvent]
        var activities: [ActivityRecord]
    }

    init(fileURL: URL?, now: @escaping () -> Date = Date.init) {
        self.fileURL = fileURL
        self.now = now
        if let fileURL, let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode(File.self, from: data) {
            plays = saved.plays
            activities = saved.activities
        }
        if fileURL != nil {
            // What waits to be written goes out before the app may be suspended.
            let writer = writer
            for name in [UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
                _ = NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { _ in writer.flush() }
            }
        }
    }

    /// Writes what is waiting now and returns once it is on disk (leaving the foreground; tests).
    func flush() { writer.flush() }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("history.json")
    }

    var nowMs: Int64 { Int64((now().timeIntervalSince1970 * 1000).rounded()) }

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
        trimAndPersist(scope, provider)
        return play.id
    }

    /// How much of the play was heard; `completed` once it counts as a play.
    func markListened(playId: String, listenedSec: Double, completed: Bool) {
        guard let i = plays.lastIndex(where: { $0.id == playId }) else { return }
        plays[i].listenedSec = max(plays[i].listenedSec, max(0, listenedSec))
        plays[i].completed = plays[i].completed || completed
        persist()
    }

    /// An album page opened (Android `recordAlbumVisit`).
    func recordAlbumVisit(scope: String, provider: String, albumId: String, title: String, artist: String,
                          artistId: String?, coverKey: String?, at ms: Int64? = nil) {
        guard !albumId.isEmpty else { return }
        activities.append(ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .album,
                                         action: .visited, entityId: albumId, title: title, subtitle: artist,
                                         coverKey: coverKey, songId: nil, albumId: albumId, artistId: artistId,
                                         timestamp: ms ?? nowMs))
        trimAndPersist(scope, provider)
    }

    /// An artist page opened (Android `recordArtistVisit`): the artist's own row, so Activities
    /// can show the artist.
    func recordArtistVisit(scope: String, provider: String, artistId: String, name: String, coverKey: String?,
                           at ms: Int64? = nil) {
        guard !artistId.isEmpty, !name.isEmpty else { return }
        activities.append(ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .artist,
                                         action: .visited, entityId: artistId, title: name, subtitle: "",
                                         coverKey: coverKey, songId: nil, albumId: nil, artistId: artistId,
                                         timestamp: ms ?? nowMs))
        trimAndPersist(scope, provider)
    }

    /// A playlist page opened (Android `recordPlaylistVisit`).
    func recordPlaylistVisit(scope: String, provider: String, playlistId: String, name: String, owner: String,
                             coverKey: String?, at ms: Int64? = nil) {
        guard !playlistId.isEmpty, !name.isEmpty else { return }
        activities.append(ActivityRecord(id: UUID().uuidString, scope: scope, provider: provider, entity: .playlist,
                                         action: .visited, entityId: playlistId, title: name, subtitle: owner,
                                         coverKey: coverKey, songId: nil, albumId: nil, artistId: nil,
                                         timestamp: ms ?? nowMs))
        trimAndPersist(scope, provider)
    }

    /// Forgets one account's history (debug seeding starts clean with it).
    func removeAll(scope: String, provider: String) {
        plays.removeAll { $0.scope == scope && $0.provider == provider }
        activities.removeAll { $0.scope == scope && $0.provider == provider }
        persist()
    }

    /// After a row of [scope] / [provider]'s: that account's oldest go past the ceilings, then the file.
    private func trimAndPersist(_ scope: String, _ provider: String) {
        Self.trim(&plays, max: Self.maxPlays) { $0.scope == scope && $0.provider == provider }
        Self.trim(&activities, max: Self.maxActivities) { $0.scope == scope && $0.provider == provider }
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
        let file = File(plays: plays, activities: activities)
        writer.write(to: fileURL) { try JSONEncoder().encode(file) }
    }

    // MARK: Queries (Android PlayHistoryDao / ActivityEventDao)

    /// One account's plays, oldest first (the order they were recorded).
    func plays(scope: String, provider: String) -> [PlayEvent] {
        plays.filter { $0.scope == scope && $0.provider == provider }
    }

    /// Newest first (`getRecentHistory`).
    func recentPlays(scope: String, provider: String, limit: Int = 50) -> [PlayEvent] {
        Array(plays(scope: scope, provider: provider).sorted { $0.startedAt > $1.startedAt }.prefix(limit))
    }

    /// The song's newest play (`getMostRecentPlay`).
    func mostRecentPlay(scope: String, provider: String, songId: String) -> PlayEvent? {
        plays.filter { $0.scope == scope && $0.provider == provider && $0.songId == songId }
            .max { $0.startedAt < $1.startedAt }
    }

    /// Every album's plays (`getAlbumAggregatesFor`), keyed by album id.
    func albumStats(scope: String, provider: String) -> [String: AlbumPlayStats] {
        Self.albumStats(plays(scope: scope, provider: provider))
    }

    func albumStats(scope: String, provider: String, albumId: String) -> AlbumPlayStats? {
        Self.albumStats(plays(scope: scope, provider: provider).filter { $0.albumId == albumId })[albumId]
    }

    /// Plays per song (the album page's per-track counts), optionally for one album.
    func songPlayCounts(scope: String, provider: String, albumId: String? = nil) -> [String: Int] {
        var counts: [String: Int] = [:]
        for p in plays where p.scope == scope && p.provider == provider && (albumId == nil || p.albumId == albumId) {
            counts[p.songId, default: 0] += 1
        }
        return counts
    }

    /// Albums by their newest play, newest first.
    func lastPlayedAlbums(scope: String, provider: String, limit: Int = 20) -> [AlbumPlayStats] {
        Array(albumStats(scope: scope, provider: provider).values
            .sorted { $0.lastPlayedAt > $1.lastPlayedAt }
            .prefix(limit))
    }

    /// Albums last played before [before] (epoch ms), longest away first.
    func albumsNotPlayedSince(_ before: Int64, scope: String, provider: String) -> [AlbumPlayStats] {
        albumStats(scope: scope, provider: provider).values
            .filter { $0.lastPlayedAt < before }
            .sorted { $0.lastPlayedAt < $1.lastPlayedAt }
    }

    /// The activity log newest first, one row per (action, entity, id): the
    /// newest (Android `getRecentActivities` → `collapseToLatestUnique`).
    func recentActivities(scope: String, provider: String, limit: Int = 20) -> [ActivityRecord] {
        let mine = activities.filter { $0.scope == scope && $0.provider == provider }
        return Array(Self.collapseToLatestUnique(mine.sorted { $0.timestamp > $1.timestamp }).prefix(limit))
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

/// The history file's writer: the newest snapshot only, encoded and written on a utility queue a second
/// after the last change, so a burst of plays and visits is one write and none of it holds the main thread.
/// A failed write keeps the history in memory for this run (the next change tries again).
final class HistoryFileWriter: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.gpo.yoin.history", qos: .utility)
    private let delay: DispatchTimeInterval
    /// Guards [pending] and [scheduled].
    private let lock = NSLock()
    private var pending: (url: URL, encode: @Sendable () throws -> Data)?
    private var scheduled = false

    init(delay: DispatchTimeInterval = .seconds(1)) { self.delay = delay }

    func write(to url: URL, _ encode: @escaping @Sendable () throws -> Data) {
        let schedule = lock.withLock {
            pending = (url, encode)
            defer { scheduled = true }
            return !scheduled
        }
        if schedule { queue.asyncAfter(deadline: .now() + delay) { [self] in drain() } }
    }

    /// Writes what is waiting, if anything, and returns once it is written.
    func flush() { queue.sync { drain() } }

    private func drain() {
        let job: (url: URL, encode: @Sendable () throws -> Data)? = lock.withLock {
            defer { pending = nil; scheduled = false }
            return pending
        }
        guard let job else { return }
        do {
            let data = try job.encode()
            try FileManager.default.createDirectory(at: job.url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try data.write(to: job.url, options: .atomic)
        } catch {}
    }
}
