import XCTest
import YoinShared
@testable import Yoin

/// Playlist cloud backup on iOS (Data/Backup): the store's file and sync
/// rows, back up and follow, and the restore — matching (Android
/// PlaylistTransferTest's rules), the batched write and the review flow —
/// against a stubbed account. The matcher, review and wire rules themselves
/// are shared Kotlin, tested in :shared.
@MainActor
final class PlaylistBackupTests: XCTestCase {
    private func tempURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("playlist-backups-\(UUID().uuidString).json")
    }

    private var clock: Int64 = 1_000

    private func store(_ url: URL? = nil) -> PlaylistBackupStore {
        PlaylistBackupStore(fileURL: url ?? tempURL()) { [unowned self] in self.clock }
    }

    private func track(_ id: String, _ title: String, _ artist: String, _ sec: Int32 = 200) -> SnapshotTrack {
        SnapshotTrack(id: "subsonic:\(id)", title: title, artists: [artist], album: "Album",
                      durationSec: KotlinInt(int: sec), isrc: nil)
    }

    private func playlist(_ tracks: [SnapshotTrack], name: String = "Commute") -> SnapshotPlaylist {
        SnapshotPlaylist(id: "subsonic:pl-1", name: name, comment: "rainy", createdAt: "2026-10-01T08:00:00Z",
                         tracks: tracks, unreadable: [])
    }

    private func source(_ lists: @escaping () -> SnapshotPlaylist?, listed: @escaping () -> [String]? = { ["subsonic:pl-1"] })
        -> PlaylistBackups.Source {
        PlaylistBackups.Source(provider: "subsonic", profileId: "http://demo|demo", label: "demo",
                               listPlaylists: {
                                   guard let l = listed() else { throw URLError(.notConnectedToInternet) }
                                   return l
                               },
                               readPlaylist: { _ in lists() })
    }

    // MARK: Store

    func test_should_keepRowsAndTheirJsonText_when_reopened() {
        let url = tempURL()
        let s = store(url)
        let record = PlaylistSnapshots.shared.backup(id: "b1", playlist: playlist([track("s1", "夜航", "林间")]),
                                                     provider: "subsonic", profileId: "here", profileLabel: "demo", now: 5)
        XCTAssertTrue(s.save(record))
        // A row from another device: its JSON text (extra fields, other spacing) is kept as received.
        let foreign = PlaylistBackupRow(id: "b2", name: "Drive", sourceProvider: "spotify", sourceProfileLabel: "Chen",
                                        sourcePlaylistId: "spotify:p", sourceProfileId: nil, playlistCreatedAt: nil,
                                        firstBackedUpAt: 1, lastBackedUpAt: 1, lastChangedAt: 9, sourceGoneAt: KotlinLong(value: 7),
                                        contentJson: #"{"tracks":[{"title":"A","sourceId":"spotify:x","mood":"sunny"}]}"#,
                                        versionsJson: "[]", restoresJson: "[]", updatedAt: 9)
        XCTAssertTrue(s.applySynced(foreign))

        let reopened = store(url)
        XCTAssertEqual(reopened.all.map(\.id), ["b2", "b1"])
        XCTAssertEqual(reopened.syncedRow("b2"), foreign)
        XCTAssertEqual(reopened.syncedRow("b1")?.sourceProfileId, "here")
        XCTAssertEqual(reopened.record("b1"), record)
        XCTAssertFalse(reopened.record("b2")!.following)
    }

    func test_should_appendRestoreAndTellSync_when_restoreIsRecorded() {
        let s = store()
        var changes = 0
        PlaylistBackupStore.onLocalChange = { changes += 1 }
        defer { PlaylistBackupStore.onLocalChange = nil }
        let record = PlaylistSnapshots.shared.backup(id: "b1", playlist: playlist([track("s1", "A", "B")]),
                                                     provider: "subsonic", profileId: nil, profileLabel: "demo", now: 5)
        s.save(record)
        clock = 50
        let restore = PlaylistRestoreRecord(at: 50, targetProvider: "subsonic", targetLabel: "demo", added: 1,
                                            skipped: 0, notFound: 0, failed: false)

        XCTAssertTrue(s.recordRestore("b1", restore))
        XCTAssertFalse(s.recordRestore("missing", restore))
        XCTAssertEqual(s.record("b1")?.restores, [restore])
        XCTAssertEqual(s.record("b1")?.updatedAt, 50)
        // Sync's own writes don't call back.
        XCTAssertTrue(s.removeSynced("b1"))
        XCTAssertNil(s.record("b1"))
        XCTAssertEqual(changes, 2)
    }

    func test_should_findTheOldestBackupOfAPlaylist_when_aSyncRaced() {
        let s = store()
        let p = playlist([track("s1", "A", "B")])
        let late = PlaylistSnapshots.shared.backup(id: "z", playlist: p, provider: "subsonic", profileId: nil,
                                                   profileLabel: "demo", now: 9)
        let early = PlaylistSnapshots.shared.backup(id: "y", playlist: p, provider: "subsonic", profileId: nil,
                                                    profileLabel: "demo", now: 3)
        s.save(late)
        s.save(early)

        XCTAssertEqual(s.findBySource(provider: "subsonic", playlistId: "subsonic:pl-1")?.id, "y")
        XCTAssertNil(s.findBySource(provider: "applemusic", playlistId: "subsonic:pl-1"))
    }

    // MARK: Back up and follow

    func test_should_backUpOnceAndRefreshThatBackup_when_backedUpAgain() async throws {
        let s = store()
        let service = PlaylistBackups(store: s) { [unowned self] in self.clock }
        var current = playlist([track("s1", "A", "B")])
        let src = source({ current })

        let first = try await service.backUp(rawId: "pl-1", from: src)
        clock = 2_000
        current = playlist([track("s1", "A", "B"), track("s2", "C", "D")], name: "Commute 2")
        let second = try await service.backUp(rawId: "pl-1", from: src)

        XCTAssertEqual(first.id, second.id)
        XCTAssertEqual(s.all.count, 1)
        XCTAssertEqual(second.name, "Commute 2")
        XCTAssertEqual(second.trackCount, 2)
        XCTAssertEqual(second.versions.count, 1)
        XCTAssertEqual(second.sourceProfileId, "http://demo|demo")
        XCTAssertEqual(second.content.description_, "rainy")
        XCTAssertEqual(second.playlistCreatedAt, "2026-10-01T08:00:00Z")
        XCTAssertEqual(second.content.tracks.first?.sourceTrackId, "subsonic:s1")
    }

    func test_should_failAndSaveNothing_when_thePlaylistIsGone() async {
        let s = store()
        let service = PlaylistBackups(store: s) { [unowned self] in self.clock }
        do {
            _ = try await service.backUp(rawId: "pl-1", from: source({ nil }))
            XCTFail("expected a failure")
        } catch {}
        XCTAssertTrue(s.all.isEmpty)
    }

    func test_should_followDueBackups_when_theAppReturns() async throws {
        let s = store()
        let service = PlaylistBackups(store: s) { [unowned self] in self.clock }
        var current: SnapshotPlaylist? = playlist([track("s1", "A", "B")])
        var listed: [String]? = ["subsonic:pl-1"]
        let src = source({ current }, listed: { listed })
        _ = try await service.backUp(rawId: "pl-1", from: src)

        // Not due yet.
        clock += 1_000
        let early = await service.followDue(src)
        XCTAssertEqual(early, [])

        // Due, unchanged; then a failed answer proves nothing; then changed; then gone.
        clock += PlaylistBackups.followIntervalMs
        let unchanged = await service.followDue(src)
        XCTAssertEqual(unchanged, [.unchanged])
        clock += PlaylistBackups.followIntervalMs
        listed = nil
        let offline = await service.followDue(src)
        XCTAssertEqual(offline, [.skipped])
        listed = ["subsonic:pl-1"]
        current = playlist([track("s9", "New", "B")])
        let changed = await service.followDue(src)
        XCTAssertEqual(changed, [.changed])
        clock += PlaylistBackups.followIntervalMs
        listed = []
        let gone = await service.followDue(src)
        XCTAssertEqual(gone, [.gone])
        XCTAssertFalse(s.all[0].following)
        XCTAssertEqual(s.all[0].content.tracks.map(\.title), ["New"])

        // Another device's account doesn't follow it here.
        let other = PlaylistBackups.Source(provider: "subsonic", profileId: "other", label: "x",
                                           listPlaylists: { [] }, readPlaylist: { _ in nil })
        clock += PlaylistBackups.followIntervalMs
        let elsewhere = await service.followDue(other)
        XCTAssertEqual(elsewhere, [])
    }

    func test_should_readSubsonicPlaylistAsAndroidDoes_when_snapshotting() {
        let ref = MediaRef(provider: "subsonic", rawId: "pl-9")
        let summary = PlaylistSummary(ref: ref, id: "pl-9", name: "Drive", owner: "demo", isPublic: nil, comment: " night ",
                                      coverArtId: "pl-9", songCount: KotlinInt(int: 1), durationSec: nil,
                                      created: "2026-01-02T00:00:00Z", changed: nil, canWrite: true,
                                      ownedByMe: KotlinBoolean(bool: true))
        let song = SongInfo(ref: MediaRef(provider: "subsonic", rawId: "s-1"), id: "s-1", title: "Night Ferry",
                            artist: "Kiri Ono", artistId: nil, album: "Harbor Lights", albumId: nil, track: nil, disc: nil,
                            durationSec: KotlinInt(int: 241), coverArtId: nil, year: nil, genre: nil, starred: false,
                            starredAt: nil, userRating: nil, suffix: nil, contentType: nil, bitRate: nil, size: nil,
                            path: nil, playCount: nil)
        let snap = PlaylistBackups.snapshot(PlaylistDetail(summary: summary, entries: [song]))
        let content = PlaylistSnapshots.shared.content(playlist: snap)

        XCTAssertEqual(snap.id, "subsonic:pl-9")
        XCTAssertEqual(content.description_, "night")
        XCTAssertEqual(content.tracks, [BackupTrack(title: "Night Ferry", artists: ["Kiri Ono"], album: "Harbor Lights",
                                                    durationSec: KotlinInt(int: 241), isrc: nil, sourceTrackId: "subsonic:s-1")])
    }

    // MARK: Restore

    func test_should_searchTheTitleAlone_when_titleAndArtistFindNothing() async throws {
        let match = MatchTrack(id: "subsonic:t1", title: "Lemon", artist: "米津玄師", album: nil,
                               durationSec: KotlinInt(int: 255), isrc: nil)
        let target = StubDestination(results: ["Lemon": [match]])
        let source = MatchTrack(id: "spotify:s1", title: "Lemon", artist: "Kenshi Yonezu", album: nil,
                                durationSec: KotlinInt(int: 255), isrc: nil)

        let resolutions = try await PlaylistRestore.resolve([source], on: target)

        // Romanised on one service, original script on the other: only the title search finds it, and the
        // artist floor still has the last word (Android PlaylistTransferTest).
        XCTAssertEqual(target.searches, ["Lemon Kenshi Yonezu", "Lemon"])
        XCTAssertTrue(resolutions[0].decision is MatchDecisionNotFound)
    }

    func test_should_matchOnceAndReuseTheSearch_when_twoSongsAskTheSame() async throws {
        let creep = MatchTrack(id: "subsonic:t1", title: "Creep", artist: "Radiohead", album: nil,
                               durationSec: KotlinInt(int: 238), isrc: nil)
        let target = StubDestination(results: ["Creep Radiohead": [creep]])
        let source = MatchTrack(id: "spotify:s1", title: "Creep", artist: "Radiohead", album: nil,
                                durationSec: KotlinInt(int: 239), isrc: nil)
        var progress: [Int] = []

        let resolutions = try await PlaylistRestore.resolve([source, source], on: target) { _, done in progress.append(done) }

        XCTAssertEqual(target.searches, ["Creep Radiohead"])
        XCTAssertEqual(progress, [1, 2])
        XCTAssertEqual((resolutions[1].decision as? MatchDecisionMatched)?.track.id, "subsonic:t1")
    }

    func test_should_leaveSongUnmatched_when_theSearchFails() async throws {
        let target = StubDestination(results: [:])
        target.searchFails = true
        let source = MatchTrack(id: "spotify:s1", title: "Creep", artist: "Radiohead", album: nil,
                                durationSec: KotlinInt(int: 238), isrc: nil)

        let resolutions = try await PlaylistRestore.resolve([source], on: target)

        XCTAssertTrue(resolutions[0].decision is MatchDecisionNotFound)
    }

    func test_should_createThenAddInBatchesAndKeepWhatLanded_when_writing() async {
        let ids = (0..<250).map { "subsonic:s\($0)" }
        let ok = StubDestination(results: [:])
        let full = await PlaylistRestore.write(name: "Commute", description: "rainy", trackIds: ids, to: ok)
        XCTAssertEqual(ok.created, ["Commute"])
        XCTAssertEqual(ok.batches.map(\.count), [100, 100, 50])
        XCTAssertEqual(full, PlaylistWrite(created: true, added: 250, requested: 250))

        let partial = StubDestination(results: [:])
        partial.failAddAfter = 1
        let half = await PlaylistRestore.write(name: "Commute", description: nil, trackIds: ids, to: partial)
        XCTAssertEqual(half, PlaylistWrite(created: true, added: 100, requested: 250))

        let refused = StubDestination(results: [:])
        refused.failCreate = true
        let none = await PlaylistRestore.write(name: "Commute", description: nil, trackIds: ids, to: refused)
        XCTAssertEqual(none, PlaylistWrite(created: false, added: 0, requested: 250))

        let empty = await PlaylistRestore.write(name: "Commute", description: nil, trackIds: [], to: ok)
        XCTAssertEqual(empty, PlaylistWrite(created: false, added: 0, requested: 0))
        XCTAssertEqual(ok.created, ["Commute"])
    }

    func test_should_reviewThenCreateAndRecordTheRestore_when_restoringIntoAnAccount() async throws {
        let s = store()
        let backup = PlaylistSnapshots.shared.backup(
            id: "b1",
            playlist: playlist([track("s1", "Creep", "Radiohead", 238), track("s2", "Nowhere", "Nobody"),
                                track("s3", "BTTB — Track 4", "Big Thief", 60)]),
            provider: "spotify", profileId: nil, profileLabel: "Chen", now: 5)
        s.save(backup)
        let creep = MatchTrack(id: "subsonic:t1", title: "Creep", artist: "Radiohead", album: nil,
                               durationSec: KotlinInt(int: 238), isrc: nil)
        let live = MatchTrack(id: "subsonic:t3", title: "BTTB — Track 4 (Live)", artist: "Big Thief", album: nil,
                              durationSec: KotlinInt(int: 60), isrc: nil)
        let picked = MatchTrack(id: "subsonic:t2", title: "Nowhere Man", artist: "Nobody", album: nil,
                                durationSec: nil, isrc: nil)
        let target = StubDestination(results: ["Creep Radiohead": [creep], "BTTB — Track 4 Big Thief": [live],
                                               "Nowhere Nobody": [], "Nowhere": [], "Nowhere Man": [picked]])
        let info = RestoreTarget(id: "subsonic:demo", label: "demo", provider: "subsonic")
        let model = PlaylistRestoreModel(backupId: "b1", store: s, destination: { _ in target }) { [unowned self] in self.clock }

        model.open()
        XCTAssertEqual(model.phase, .choosing)
        XCTAssertTrue(model.sheetOpen)
        model.restore(to: info)
        try await waitFor { if case .review = model.phase { true } else { false } }
        guard case .review(_, let rows) = model.phase else { return XCTFail() }
        XCTAssertEqual(rows.map(\.status), [.matched, .notfound, .uncertain])
        XCTAssertEqual(model.idsToWrite, ["subsonic:t1"])

        // The live take is offered, not added: the user checks it; the song not found is searched for.
        model.setChecked(2, true)
        model.openSearch(1, query: "Nowhere Man")
        try await waitFor { model.search?.results.isEmpty == false }
        model.pick(model.search!.results[0])
        XCTAssertNil(model.search)
        XCTAssertEqual(model.idsToWrite, ["subsonic:t1", "subsonic:t2", "subsonic:t3"])

        clock = 77
        model.create()
        try await waitFor { if case .done = model.phase { true } else { false } }
        XCTAssertEqual(target.created, ["Commute"])
        XCTAssertEqual(target.batches, [["subsonic:t1", "subsonic:t2", "subsonic:t3"]])
        let history = s.record("b1")!.restores
        XCTAssertEqual(history, [PlaylistRestoreRecord(at: 77, targetProvider: "subsonic", targetLabel: "demo",
                                                       added: 3, skipped: 0, notFound: 0, failed: false)])
        model.sheetClosed()
        XCTAssertEqual(model.phase, .idle)
    }

    func test_should_dropTheRestore_when_cancelledWhileMatching() async throws {
        let s = store()
        s.save(PlaylistSnapshots.shared.backup(id: "b1", playlist: playlist([track("s1", "A", "B")]), provider: "subsonic",
                                               profileId: nil, profileLabel: "demo", now: 5))
        let target = StubDestination(results: [:])
        target.searchDelay = .seconds(5)
        let model = PlaylistRestoreModel(backupId: "b1", store: s, destination: { _ in target })
        model.open()
        model.restore(to: RestoreTarget(id: "t", label: "demo", provider: "subsonic"))
        model.cancel()
        XCTAssertEqual(model.phase, .idle)
        XCTAssertFalse(model.sheetOpen)
        // An unavailable account can't be chosen.
        model.open()
        model.restore(to: RestoreTarget(id: "am", label: "Apple Music", provider: "applemusic", available: false))
        XCTAssertEqual(model.phase, .choosing)
    }

    // MARK: Texts

    func test_should_writeDatesAsAndroidDoes_when_formatting() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let locale = Locale(identifier: "en_US")
        let now = Date(timeIntervalSince1970: 1_792_000_000) // 2026-10
        let thisYear: Int64 = 1_790_000_000_000 // 2026-09-21
        let lastYear: Int64 = 1_760_000_000_000 // 2025-10-09
        let same = BackupTexts.time(thisYear, now: now, locale: locale, calendar: cal)
        XCTAssertTrue(same.hasPrefix("Sep 21"), same)
        XCTAssertFalse(same.contains("2026"), same)
        XCTAssertTrue(BackupTexts.time(lastYear, now: now, locale: locale, calendar: cal).contains("2025"))
        XCTAssertEqual(BackupTexts.serviceDate("2024-03-01T10:00:00Z"), 1_709_287_200_000)
        XCTAssertEqual(BackupTexts.serviceDate("2024-03-01T10:00:00.500Z"), 1_709_287_200_500)
        XCTAssertEqual(BackupTexts.serviceDate("2024-03-01"), 1_709_251_200_000)
        XCTAssertNil(BackupTexts.serviceDate("soon"))
        let a = BackupTrack(title: "A", artists: [], album: nil, durationSec: nil, isrc: nil, sourceTrackId: "x:1")
        let b = BackupTrack(title: "B", artists: [], album: nil, durationSec: nil, isrc: nil, sourceTrackId: "x:2")
        let c = BackupTrack(title: "C", artists: [], album: nil, durationSec: nil, isrc: nil, sourceTrackId: "x:3")
        let diff = BackupTexts.versionDiff(older: [a, b], newer: [b, c])
        XCTAssertEqual(diff.added, 1)
        XCTAssertEqual(diff.removed, 1)
    }

    private func waitFor(_ condition: @escaping @MainActor () -> Bool, timeout: Double = 5) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { return XCTFail("timed out") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}

/// A restore target with canned search results and a recorded write.
@MainActor
private final class StubDestination: RestoreDestination {
    var results: [String: [MatchTrack]]
    var searches: [String] = []
    var searchFails = false
    var searchDelay: Duration?
    var failCreate = false
    /// Batches that land before one fails (nil: none fails).
    var failAddAfter: Int?
    var created: [String] = []
    var batches: [[String]] = []

    init(results: [String: [MatchTrack]]) { self.results = results }

    func search(_ query: String) async throws -> [MatchTrack] {
        searches.append(query)
        if let searchDelay { try await Task.sleep(for: searchDelay) }
        if searchFails { throw URLError(.timedOut) }
        return results[query] ?? []
    }

    func createPlaylist(name: String, description: String?) async throws -> String {
        if failCreate { throw URLError(.badServerResponse) }
        created.append(name)
        return "new-1"
    }

    func add(_ trackIds: [String], to playlistId: String) async throws {
        if let failAddAfter, batches.count >= failAddAfter { throw URLError(.timedOut) }
        batches.append(trackIds)
    }
}
