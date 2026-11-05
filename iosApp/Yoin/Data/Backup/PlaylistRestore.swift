import Foundation
import Observation
import SwiftUI
import YoinShared

/// Where a restore writes (Android MusicSource's `library().search` and
/// `writeActions().createPlaylistWithTracks`): one account's search and a new
/// playlist. Ids are `provider:rawId`. Each destination is one account, chosen
/// once: matching, the review's search and the write all go to it, whichever
/// account is in use meanwhile.
@MainActor
protocol RestoreDestination: AnyObject {
    func search(_ query: String) async throws -> [MatchTrack]
    /// A new playlist holding [trackIds] (at most `PlaylistRestore.addBatch`, maybe none), in order; its id
    /// on the service.
    func createPlaylist(name: String, description: String?, trackIds: [String]) async throws -> String
    func add(_ trackIds: [String], to playlistId: String) async throws
    /// The restore is over: lets go of what it opened.
    func close()
}

extension RestoreDestination {
    func close() {}
}

/// An account a backup can be restored into: every account configured here
/// whose service takes a new playlist (Android `restoreTargets`, capability
/// PLAYLIST_IMPORT) — Subsonic servers and Apple Music. [id] is the account's
/// profile id; one that can't be used now (no sign-in, Apple Music access off)
/// is listed but can't be chosen.
struct RestoreTarget: Identifiable, Hashable, Sendable {
    var id: String
    var label: String
    var provider: String
    /// False: shown, can't be chosen ([note] says why).
    var available: Bool = true
    var note: String?
}

/// The restore rules (Android PlaylistTransfer.resolve / writeTracks), around
/// the shared TrackMatcher and RestoreReview.
@MainActor
enum PlaylistRestore {
    /// Android PLAYLIST_ADD_BATCH.
    static let addBatch = 100

    /// Each of [tracks] (the backup's, in order) resolved on [target]: a title + artist search scored by
    /// TrackMatcher, then the title alone when that found nothing. A search that fails leaves its track
    /// unmatched rather than failing the restore; cancellation still cancels. (No ISRC lookup: no iOS
    /// source looks one up; Android's Spotify-by-id path has no iOS target.)
    static func resolve(
        _ tracks: [MatchTrack],
        on target: RestoreDestination,
        onResolved: (TrackResolution, Int) -> Void = { _, _ in }
    ) async throws -> [TrackResolution] {
        var searches: [String: [MatchTrack]] = [:]
        func search(_ q: String) async throws -> [MatchTrack] {
            if let hit = searches[q] { return hit }
            let found: [MatchTrack]
            do { found = try await target.search(q) } catch { found = [] }
            try Task.checkCancellation()
            searches[q] = found
            return found
        }
        var out: [TrackResolution] = []
        let matcher = TrackMatcher.shared
        for (i, track) in tracks.enumerated() {
            try Task.checkCancellation()
            var decision: any MatchDecision = MatchDecisionNotFound.shared
            if let query = matcher.searchQuery(track: track) {
                decision = matcher.choose(source: track, candidates: try await search(query))
                // The artist as the target spells it can sink "title artist" (a romanised name against
                // the original script): the title alone, then.
                if decision is MatchDecisionNotFound, let title = matcher.titleQuery(track: track), title != query {
                    decision = matcher.choose(source: track, candidates: try await search(title))
                }
            }
            let resolution = TrackResolution(position: Int32(i), source: track, decision: decision)
            out.append(resolution)
            onResolved(resolution, i + 1)
        }
        return out
    }

    /// A playlist named [name] on [target] holding [trackIds] in order (Android createPlaylistWithTracks:
    /// the create carries the first batch, the rest are appended in batches; a failure after the create
    /// keeps what was added). None: nothing made.
    static func write(name: String, description: String?, trackIds: [String], to target: RestoreDestination) async -> PlaylistWrite {
        let requested = Int32(trackIds.count)
        guard !trackIds.isEmpty else { return PlaylistWrite(created: false, added: 0, requested: 0) }
        let first = Array(trackIds.prefix(addBatch))
        guard let id = try? await target.createPlaylist(name: name, description: description, trackIds: first) else {
            return PlaylistWrite(created: false, added: 0, requested: requested)
        }
        var added = Int32(first.count)
        for start in stride(from: first.count, to: trackIds.count, by: addBatch) {
            let batch = Array(trackIds[start..<min(start + addBatch, trackIds.count)])
            do { try await target.add(batch, to: id) } catch { break }
            added += Int32(batch.count)
        }
        return PlaylistWrite(created: true, added: added, requested: requested)
    }
}

/// A Subsonic account as a restore target, on a client of its own built from that account's sign-in
/// (never the account in use's client, which a switch closes or replaces).
@MainActor
final class SubsonicRestoreDestination: RestoreDestination {
    private let client: LazySubsonicClient

    init(account: ServerSession.Account) { client = LazySubsonicClient(account) }

    func search(_ query: String) async throws -> [MatchTrack] {
        try await client.get().search3(query: query, artistCount: 0, albumCount: 0, songCount: 20).songs.map(Self.track)
    }

    func createPlaylist(name: String, description: String?, trackIds: [String]) async throws -> String {
        let raw = trackIds.compactMap { PlaylistSnapshots.shared.rawIdOf(id: $0) }
        guard let made = try await client.get().createPlaylist(name: name, comment: description, songIds: raw) else {
            throw PlaylistBackups.BackupError.notFound
        }
        return made.summary.id
    }

    func add(_ trackIds: [String], to playlistId: String) async throws {
        let raw = trackIds.compactMap { PlaylistSnapshots.shared.rawIdOf(id: $0) }
        try await client.get().updatePlaylist(id: playlistId, name: nil, comment: nil, isPublic: nil,
                                              songIdsToAdd: raw, songIndexesToRemove: [])
    }

    func close() { client.close() }

    nonisolated static func track(_ s: SongInfo) -> MatchTrack {
        MatchTrack(id: "\(s.ref.provider):\(s.id)", title: s.title, artist: s.artist, album: s.album,
                   durationSec: s.durationSec, isrc: nil)
    }
}

/// This device's Apple Music as a restore target (Android AppleMusicSource's search and
/// createPlaylistWithTracks): the catalog searched, a new playlist in the user's library.
@MainActor
final class AppleMusicRestoreDestination: RestoreDestination {
    let apple: AppleMusicSource

    init(apple: AppleMusicSource) { self.apple = apple }

    func search(_ query: String) async throws -> [MatchTrack] {
        try await apple.searchSongs(query).map(Self.track)
    }

    func createPlaylist(name: String, description: String?, trackIds: [String]) async throws -> String {
        try await apple.createLibraryPlaylist(name: name, description: description, trackIds: trackIds)
    }

    func add(_ trackIds: [String], to playlistId: String) async throws {
        try await apple.addToLibraryPlaylist(trackIds, playlistRawId: playlistId)
    }

    nonisolated static func track(_ s: AMSong) -> MatchTrack {
        let seconds = (s.durationMs ?? 0) / 1000
        return MatchTrack(id: "\(AppleMusicMapping.provider):\(s.rawId)", title: s.title,
                          artist: s.artist.isEmpty ? nil : s.artist, album: s.album.isEmpty ? nil : s.album,
                          durationSec: seconds > 0 ? KotlinInt(int: Int32(seconds)) : nil, isrc: nil)
    }
}

/// One backup's restore, from choosing where to through what came of it
/// (Android PlaylistBackupViewModel's restore, docs/playlist-transfer.md §7).
/// One sheet shows every step; closing it leaves matching running or the
/// review waiting, and Restore reopens it there. Lives as long as the backup's
/// page.
@MainActor
@Observable
final class PlaylistRestoreModel {
    enum Phase: Equatable {
        case idle
        case choosing
        /// [outcomes]: what each song resolved to so far, in order (the progress bar's segments).
        case matching(RestoreTarget, done: Int, total: Int, outcomes: [ReviewStatus])
        /// Nothing written yet: the user checks the rows to write.
        case review(RestoreTarget, rows: [ReviewRow])
        case writing(RestoreTarget, rows: [ReviewRow])
        case done(RestoreTarget, record: PlaylistRestoreRecord)
        /// Matching couldn't reach the target.
        case failed(RestoreTarget)
    }

    /// The review's search for one song on the target.
    struct Search: Equatable {
        var position: Int32
        var query: String
        var results: [MatchTrack] = []
        var loading = false
        var failed = false
    }

    let backupId: String
    private(set) var phase: Phase = .idle
    var sheetOpen = false
    private(set) var search: Search?
    /// Which version a restore takes: nil = the current one, else that version's `since`.
    private(set) var versionSince: Int64?

    @ObservationIgnored private let store: PlaylistBackupStore
    @ObservationIgnored private let destination: @MainActor (RestoreTarget) -> RestoreDestination?
    @ObservationIgnored private let now: () -> Int64
    /// The account chosen at Restore to, opened once: matching, the review's search and the write all use
    /// it (never looked up again, so a switch meanwhile can't send the write elsewhere).
    @ObservationIgnored private var chosen: (target: RestoreTarget, destination: RestoreDestination)?
    @ObservationIgnored private var job: Task<Void, Never>?
    @ObservationIgnored private var searchJob: Task<Void, Never>?

    init(backupId: String, store: PlaylistBackupStore = .shared,
         destination: @escaping @MainActor (RestoreTarget) -> RestoreDestination?,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.backupId = backupId
        self.store = store
        self.destination = destination
        self.now = now
    }

    /// The backup as the restore sees it: its current contents, or the chosen earlier version's.
    var backup: PlaylistBackupRecord? {
        store.record(backupId)?.atVersion(since: versionSince.map { KotlinLong(value: $0) })
    }

    /// Restore: a fresh choice of target (of the current contents, or of the version that began at
    /// [since]), or back to the restore under way.
    func open(version since: Int64? = nil) {
        switch phase {
        case .idle, .done, .failed:
            versionSince = since
            phase = .choosing
        default: break
        }
        withAnimation(Yoin.spatial) { sheetOpen = true }
    }

    /// The sheet went down: a finished restore is put away, one under way carries on.
    func sheetClosed() {
        sheetOpen = false
        switch phase {
        case .choosing, .done, .failed: phase = .idle
        default: break
        }
    }

    func restore(to target: RestoreTarget) {
        guard target.available, let backup, job == nil, let dest = destination(target) else { return }
        release()
        chosen = (target, dest)
        let tracks = backup.content.tracks.map { PlaylistSnapshots.shared.toMatchTrack(track: $0) }
        let total = tracks.count
        withAnimation(Yoin.spatial) { phase = .matching(target, done: 0, total: total, outcomes: []) }
        job = Task { [weak self] in
            var outcomes: [ReviewStatus] = []
            do {
                let resolutions = try await PlaylistRestore.resolve(tracks, on: dest) { resolution, done in
                    outcomes.append(RestoreReview.shared.statusOf(resolution: resolution))
                    guard let self, case .matching = self.phase else { return }
                    withAnimation(Yoin.effects) {
                        self.phase = .matching(target, done: done, total: total, outcomes: outcomes)
                    }
                }
                guard let self else { return }
                withAnimation(Yoin.spatial) {
                    self.phase = .review(target, rows: RestoreReview.shared.rows(resolutions: resolutions))
                }
            } catch {
                guard let self, !Task.isCancelled else { return }
                withAnimation(Yoin.spatial) { self.phase = .failed(target) }
                self.release()
            }
            self?.job = nil
        }
    }

    /// The account [target] was opened as at Restore to; nil once that restore is over.
    private func chosenDestination(_ target: RestoreTarget) -> RestoreDestination? {
        guard let chosen, chosen.target == target else { return nil }
        return chosen.destination
    }

    /// The restore's account is let go (it is over, or another begins).
    private func release() {
        chosen?.destination.close()
        chosen = nil
    }

    func setChecked(_ position: Int32, _ checked: Bool) {
        updateReview { RestoreReview.shared.setChecked(rows: $0, position: position, checked: checked) }
    }

    func choose(_ position: Int32, _ index: Int32) {
        updateReview { RestoreReview.shared.choose(rows: $0, position: position, index: index) }
    }

    var idsToWrite: [String] {
        guard case .review(_, let rows) = phase else { return [] }
        return RestoreReview.shared.trackIdsToWrite(rows: rows)
    }

    /// Writes the checked rows to the target; the backup's history records it. Once started, the write and
    /// its history entry finish even if the page closes.
    func create() {
        guard let backup, case .review(let target, let rows) = phase, let dest = chosenDestination(target) else { return }
        let ids = RestoreReview.shared.trackIdsToWrite(rows: rows)
        guard !ids.isEmpty else { return }
        closeSearch()
        withAnimation(Yoin.spatial) { phase = .writing(target, rows: rows) }
        let name = backup.name, description = backup.content.description_, id = backup.id
        let store = store, now = now
        Task { [weak self] in
            let write = await PlaylistRestore.write(name: name, description: description, trackIds: ids, to: dest)
            let record = RestoreReview.shared.restoreRecord(targetProvider: target.provider, targetLabel: target.label,
                                                            rows: rows, write: write, now: now())
            store.recordRestore(id, record)
            dest.close()
            guard let self else { return }
            if self.chosen?.destination === dest { self.chosen = nil }
            withAnimation(Yoin.spatial) { self.phase = .done(target, record: record) }
        }
    }

    /// Drops the restore under way (a review nothing was written from) and closes the sheet.
    func cancel() {
        if case .writing = phase { return }
        job?.cancel()
        job = nil
        closeSearch()
        release()
        phase = .idle
        withAnimation(Yoin.spatial) { sheetOpen = false }
    }

    // MARK: Search

    /// Opens the search for the song at [position] on the restore's target, starting from its title and artist.
    func openSearch(_ position: Int32, query: String) {
        withAnimation(Yoin.spatial) { search = Search(position: position, query: query) }
        runSearch(query, debounce: false)
    }

    func runSearch(_ query: String, debounce: Bool = true) {
        guard case .review(let target, _) = phase, let current = search, let dest = chosenDestination(target) else { return }
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        search = Search(position: current.position, query: query, results: current.results, loading: !trimmed.isEmpty)
        searchJob?.cancel()
        guard !trimmed.isEmpty else { return }
        searchJob = Task { [weak self] in
            if debounce { try? await Task.sleep(for: .milliseconds(350)) }
            guard !Task.isCancelled else { return }
            let results = try? await dest.search(trimmed)
            guard !Task.isCancelled, let self, self.search?.position == current.position else { return }
            withAnimation(Yoin.effects) {
                self.search?.results = results ?? []
                self.search?.loading = false
                self.search?.failed = results == nil
            }
        }
    }

    /// [track] becomes the searched row's song, checked; the search closes.
    func pick(_ track: MatchTrack) {
        guard let position = search?.position else { return }
        updateReview { RestoreReview.shared.pick(rows: $0, position: position, track: track) }
        closeSearch()
    }

    func closeSearch() {
        searchJob?.cancel()
        withAnimation(Yoin.spatial) { search = nil }
    }

    private func updateReview(_ change: ([ReviewRow]) -> [ReviewRow]) {
        guard case .review(let target, let rows) = phase else { return }
        withAnimation(Yoin.effects) { phase = .review(target, rows: change(rows)) }
    }
}
