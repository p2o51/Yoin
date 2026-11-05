import Foundation
import Observation
import SwiftUI
import YoinShared

/// Where a restore writes (Android MusicSource's `library().search` and
/// `writeActions().createPlaylistWithTracks`): one account's search and a new
/// playlist. Ids are `provider:rawId`.
@MainActor
protocol RestoreDestination: AnyObject {
    func search(_ query: String) async throws -> [MatchTrack]
    /// A new, empty playlist; its id on the service.
    func createPlaylist(name: String, description: String?) async throws -> String
    func add(_ trackIds: [String], to playlistId: String) async throws
}

/// An account a backup can be restored into. iOS has one account per service
/// at a time: the Subsonic server in use. Apple Music isn't one here — the
/// iOS Apple Music source has no playlist create (Android's has; capability
/// PLAYLIST_IMPORT), so it is listed as unavailable.
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
    /// create, then append in batches; a failure after the create keeps what was added). None: nothing made.
    static func write(name: String, description: String?, trackIds: [String], to target: RestoreDestination) async -> PlaylistWrite {
        let requested = Int32(trackIds.count)
        guard !trackIds.isEmpty else { return PlaylistWrite(created: false, added: 0, requested: 0) }
        guard let id = try? await target.createPlaylist(name: name, description: description) else {
            return PlaylistWrite(created: false, added: 0, requested: requested)
        }
        var added: Int32 = 0
        for start in stride(from: 0, to: trackIds.count, by: addBatch) {
            let batch = Array(trackIds[start..<min(start + addBatch, trackIds.count)])
            do { try await target.add(batch, to: id) } catch { break }
            added += Int32(batch.count)
        }
        return PlaylistWrite(created: true, added: added, requested: requested)
    }
}

/// The Subsonic server in use as a restore target.
@MainActor
final class SubsonicRestoreDestination: RestoreDestination {
    let client: SubsonicClient

    init(client: SubsonicClient) { self.client = client }

    func search(_ query: String) async throws -> [MatchTrack] {
        try await client.search3(query: query, artistCount: 0, albumCount: 0, songCount: 20).songs.map(Self.track)
    }

    func createPlaylist(name: String, description: String?) async throws -> String {
        guard let made = try await client.createPlaylist(name: name, comment: description, songIds: []) else {
            throw PlaylistBackups.BackupError.notFound
        }
        return made.summary.id
    }

    func add(_ trackIds: [String], to playlistId: String) async throws {
        let raw = trackIds.compactMap { PlaylistSnapshots.shared.rawIdOf(id: $0) }
        try await client.updatePlaylist(id: playlistId, name: nil, comment: nil, isPublic: nil,
                                        songIdsToAdd: raw, songIndexesToRemove: [])
    }

    nonisolated static func track(_ s: SongInfo) -> MatchTrack {
        MatchTrack(id: "\(s.ref.provider):\(s.id)", title: s.title, artist: s.artist, album: s.album,
                   durationSec: s.durationSec, isrc: nil)
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
            }
            self?.job = nil
        }
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
        guard let backup, case .review(let target, let rows) = phase, let dest = destination(target) else { return }
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
            withAnimation(Yoin.spatial) { self?.phase = .done(target, record: record) }
        }
    }

    /// Drops the restore under way (a review nothing was written from) and closes the sheet.
    func cancel() {
        if case .writing = phase { return }
        job?.cancel()
        job = nil
        closeSearch()
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
        guard case .review(let target, _) = phase, let current = search, let dest = destination(target) else { return }
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
