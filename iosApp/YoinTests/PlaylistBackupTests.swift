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

    private func source(_ lists: @escaping () -> SnapshotPlaylist?, listed: @escaping () -> [String]? = { ["subsonic:pl-1"] },
                        profileId: String = "p-demo", label: String = "demo") -> PlaylistBackups.Source {
        PlaylistBackups.Source(provider: "subsonic", profileId: profileId, label: label,
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

        XCTAssertEqual(s.bySource(provider: "subsonic", playlistId: "subsonic:pl-1").map(\.id), ["y", "z"])
        XCTAssertTrue(s.bySource(provider: "applemusic", playlistId: "subsonic:pl-1").isEmpty)
    }

    // MARK: Back up and follow

    func test_should_backUpOnceAndRefreshThatBackup_when_backedUpAgain() async throws {
        let s = store()
        let service = PlaylistBackups(store: s, accounts: StubAccounts([subsonic("p-demo")])) { [unowned self] in self.clock }
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
        XCTAssertEqual(second.sourceProfileId, "p-demo")
        XCTAssertEqual(second.content.description_, "rainy")
        XCTAssertEqual(second.playlistCreatedAt, "2026-10-01T08:00:00Z")
        XCTAssertEqual(second.content.tracks.first?.sourceTrackId, "subsonic:s1")
    }

    func test_should_failAndSaveNothing_when_thePlaylistIsGone() async {
        let s = store()
        let service = PlaylistBackups(store: s, accounts: StubAccounts([])) { [unowned self] in self.clock }
        do {
            _ = try await service.backUp(rawId: "pl-1", from: source({ nil }))
            XCTFail("expected a failure")
        } catch {}
        XCTAssertTrue(s.all.isEmpty)
    }

    func test_should_followDueBackups_when_theAppReturns() async throws {
        let s = store()
        var current: SnapshotPlaylist? = playlist([track("s1", "A", "B")])
        var listed: [String]? = ["subsonic:pl-1"]
        let src = source({ current }, listed: { listed })
        let accounts = StubAccounts([subsonic("p-demo")])
        accounts.sources["p-demo"] = src
        let service = PlaylistBackups(store: s, accounts: accounts) { [unowned self] in self.clock }
        _ = try await service.backUp(rawId: "pl-1", from: src)

        // Not due yet.
        clock += 1_000
        let early = await service.followDue()
        XCTAssertEqual(early, [])

        // Due, unchanged; then a failed answer proves nothing; then changed; then gone.
        clock += PlaylistBackups.followIntervalMs
        let unchanged = await service.followDue()
        XCTAssertEqual(unchanged, [.unchanged])
        clock += PlaylistBackups.followIntervalMs
        listed = nil
        let offline = await service.followDue()
        XCTAssertEqual(offline, [.skipped])
        listed = ["subsonic:pl-1"]
        current = playlist([track("s9", "New", "B")])
        let changed = await service.followDue()
        XCTAssertEqual(changed, [.changed])
        clock += PlaylistBackups.followIntervalMs
        listed = []
        let gone = await service.followDue()
        XCTAssertEqual(gone, [.gone])
        XCTAssertFalse(s.all[0].following)
        XCTAssertEqual(s.all[0].content.tracks.map(\.title), ["New"])
        XCTAssertEqual(accounts.closed, accounts.opened, "every source opened is closed")
    }

    /// Codex N19: a delete while the read is in flight must not be undone by the follow's write.
    func test_should_notBringBackABackup_when_itIsDeletedWhileFollowing() async throws {
        let s = store()
        let service = PlaylistBackups(store: s, accounts: StubAccounts([subsonic("p-demo")])) { [unowned self] in self.clock }
        let backup = try await service.backUp(rawId: "pl-1", from: source({ self.playlist([self.track("s1", "A", "B")]) }))
        let slow = source({ [unowned s] in
            s.delete(backup.id)
            return self.playlist([self.track("s9", "New", "B")])
        })

        let changed = await service.follow(backup, source: slow, listed: ["subsonic:pl-1"])
        XCTAssertEqual(changed, .skipped)
        XCTAssertNil(s.record(backup.id))

        let again = try await service.backUp(rawId: "pl-1", from: source({ self.playlist([self.track("s1", "A", "B")]) }))
        s.delete(again.id)
        let gone = await service.follow(again, source: slow, listed: [])
        XCTAssertEqual(gone, .skipped)
        XCTAssertNil(s.record(again.id))
    }

    /// Codex #17: each due backup is read from its own account, whichever account is in use; an account
    /// that can't be read now leaves its backups for later, and one not configured here never follows.
    func test_should_followEachBackupOnItsOwnAccount_when_anotherAccountIsInUse() async throws {
        let s = store()
        let accounts = StubAccounts([subsonic("p-a", user: "alice"), subsonic("p-b", user: "bob")], active: "p-b")
        var aRead = 0, bRead = 0
        let a = source({ aRead += 1; return self.playlist([self.track("s1", "A", "B")]) }, profileId: "p-a", label: "alice")
        let b = source({ bRead += 1; return self.playlist([self.track("s1", "A", "B")]) }, profileId: "p-b", label: "bob")
        accounts.sources = ["p-a": a, "p-b": b]
        let service = PlaylistBackups(store: s, accounts: accounts) { [unowned self] in self.clock }
        _ = try await service.backUp(rawId: "pl-1", from: a)
        // Another device's backup of the same id: no account here follows it.
        s.save(PlaylistSnapshots.shared.backup(id: "elsewhere", playlist: playlist([track("s1", "A", "B")]),
                                               provider: "subsonic", profileId: "gone-profile", profileLabel: "carol", now: 1))
        aRead = 0

        clock += PlaylistBackups.followIntervalMs
        let results = await service.followDue()
        XCTAssertEqual(results, [.unchanged])
        XCTAssertEqual(aRead, 1)
        XCTAssertEqual(bRead, 0)
        XCTAssertTrue(service.followsHere(s.all.first { $0.sourceProfileId == "p-a" }!))

        // A's sign-in can't be read now: nothing is lost, it waits.
        accounts.unreadable = ["p-a"]
        clock += PlaylistBackups.followIntervalMs
        let waiting = await service.followDue()
        XCTAssertEqual(waiting, [.skipped])
        XCTAssertFalse(service.followsHere(s.all.first { $0.sourceProfileId == "p-a" }!))
        do {
            _ = try await service.followNow(s.record("elsewhere")!)
            XCTFail("expected notHere")
        } catch {}
    }

    /// Codex #5: playlist "pl-1" on server A and "pl-1" on server B are two backups; B never takes over A's.
    /// A row written before accounts had ids ("baseUrl|username") still belongs to its account.
    func test_should_keepOneBackupPerAccount_when_twoServersShareAPlaylistId() async throws {
        let s = store()
        let profileA = subsonic("p-a", user: "alice", url: "http://a"), profileB = subsonic("p-b", user: "bob", url: "http://b")
        let accounts = StubAccounts([profileA, profileB])
        let service = PlaylistBackups(store: s, accounts: accounts) { [unowned self] in self.clock }
        let a = source({ self.playlist([self.track("s1", "A", "B")], name: "On A") }, profileId: "p-a", label: "alice")
        var b = source({ self.playlist([self.track("s7", "C", "D")], name: "On B") }, profileId: "p-b", label: "bob")
        b.formerIds = PlaylistBackups.formerIds(of: profileB)

        let onA = try await service.backUp(rawId: "pl-1", from: a)
        let onB = try await service.backUp(rawId: "pl-1", from: b)
        XCTAssertNotEqual(onA.id, onB.id)
        XCTAssertEqual(s.record(onA.id)?.name, "On A")
        XCTAssertEqual(s.record(onA.id)?.sourceProfileId, "p-a")
        XCTAssertEqual(s.record(onB.id)?.sourceProfileId, "p-b")
        let again = try await service.backUp(rawId: "pl-1", from: b)
        XCTAssertEqual(again.id, onB.id)
        XCTAssertEqual(s.all.count, 2)

        // An earlier build's row for B, keyed by its server + user: B's, moved to B's id on its next backup.
        let s2 = store()
        s2.save(PlaylistSnapshots.shared.backup(id: "old-b", playlist: playlist([track("s1", "A", "B")]), provider: "subsonic",
                                                profileId: "http://b|bob", profileLabel: "bob", now: 1))
        s2.save(PlaylistSnapshots.shared.backup(id: "old-a", playlist: playlist([track("s1", "A", "B")]), provider: "subsonic",
                                                profileId: "http://a|alice", profileLabel: "alice", now: 0))
        let service2 = PlaylistBackups(store: s2, accounts: accounts) { [unowned self] in self.clock }
        let migrated = try await service2.backUp(rawId: "pl-1", from: b)
        XCTAssertEqual(migrated.id, "old-b")
        XCTAssertEqual(s2.record("old-b")?.sourceProfileId, "p-b")
        XCTAssertEqual(s2.record("old-a")?.sourceProfileId, "http://a|alice")

        // A row from another device (no account named): adopted, preferring the one under this account's name.
        let s3 = store()
        s3.save(PlaylistSnapshots.shared.backup(id: "synced-x", playlist: playlist([track("s1", "A", "B")]),
                                                provider: "subsonic", profileId: nil, profileLabel: "x @ elsewhere", now: 0))
        s3.save(PlaylistSnapshots.shared.backup(id: "synced-bob", playlist: playlist([track("s1", "A", "B")]),
                                                provider: "subsonic", profileId: nil, profileLabel: "bob", now: 2))
        let service3 = PlaylistBackups(store: s3, accounts: accounts) { [unowned self] in self.clock }
        let adoptedByB = try await service3.backUp(rawId: "pl-1", from: b)
        let adoptedByA = try await service3.backUp(rawId: "pl-1", from: a)
        XCTAssertEqual(adoptedByB.id, "synced-bob")
        XCTAssertEqual(adoptedByA.id, "synced-x")
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
        XCTAssertEqual(ok.createdWith.map(\.count), [100])
        XCTAssertEqual(ok.batches.map(\.count), [100, 50])
        XCTAssertEqual(full, PlaylistWrite(created: true, added: 250, requested: 250))

        let partial = StubDestination(results: [:])
        partial.failAddAfter = 1
        let half = await PlaylistRestore.write(name: "Commute", description: nil, trackIds: ids, to: partial)
        XCTAssertEqual(half, PlaylistWrite(created: true, added: 200, requested: 250))

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
        XCTAssertEqual(target.createdWith, [["subsonic:t1", "subsonic:t2", "subsonic:t3"]])
        XCTAssertTrue(target.batches.isEmpty)
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

    /// Codex #2: the account chosen at Restore to is the one written to, even if the lookup would now
    /// answer with another account (a switch in another window); it is let go once the restore is over.
    func test_should_writeToTheChosenAccount_when_theAccountInUseChangesMidRestore() async throws {
        let s = store()
        s.save(PlaylistSnapshots.shared.backup(id: "b1", playlist: playlist([track("s1", "Creep", "Radiohead", 238)]),
                                               provider: "spotify", profileId: nil, profileLabel: "Chen", now: 5))
        let creep = MatchTrack(id: "subsonic:t1", title: "Creep", artist: "Radiohead", album: nil,
                               durationSec: KotlinInt(int: 238), isrc: nil)
        let chosen = StubDestination(results: ["Creep Radiohead": [creep]])
        let other = StubDestination(results: ["Creep Radiohead": [creep]])
        var lookup: StubDestination = chosen
        var lookups = 0
        let model = PlaylistRestoreModel(backupId: "b1", store: s, destination: { _ in lookups += 1; return lookup })
        model.open()
        model.restore(to: RestoreTarget(id: "p-a", label: "alice", provider: "subsonic"))
        try await waitFor { if case .review = model.phase { true } else { false } }

        lookup = other
        model.openSearch(0, query: "Creep")
        try await waitFor { model.search?.loading == false }
        model.closeSearch()
        model.create()
        try await waitFor { if case .done = model.phase { true } else { false } }

        XCTAssertEqual(lookups, 1)
        XCTAssertEqual(chosen.created, ["Commute"])
        XCTAssertEqual(chosen.searches, ["Creep Radiohead", "Creep"])
        XCTAssertTrue(other.created.isEmpty && other.searches.isEmpty)
        XCTAssertTrue(chosen.closed)
        XCTAssertEqual(s.record("b1")?.restores.last?.targetLabel, "alice")
    }

    /// Codex #18: every configured account that takes a playlist is offered, the one in use or not;
    /// one that can't be used now is listed, not choosable; the restore opens the account it names.
    func test_should_offerEveryConfiguredAccount_when_choosingWhereToRestore() {
        let accounts = StubAccounts([subsonic("p-a", user: "alice", url: "http://a.example"),
                                     subsonic("p-b", user: "bob", url: "http://b.example"),
                                     Profile(id: "ios-applemusic", service: .appleMusic, createdAt: 3)], active: "p-a")
        accounts.unreadable = ["p-b"]
        let targets = BackupTexts.targets(accounts)
        XCTAssertEqual(targets.map(\.id), ["p-a", "p-b", "ios-applemusic"])
        XCTAssertEqual(targets.map(\.available), [true, false, true])
        XCTAssertEqual(targets.map(\.provider), ["subsonic", "subsonic", "applemusic"])
        XCTAssertEqual(targets[0].note, "a.example")

        _ = BackupTexts.destination(for: targets[2], accounts: accounts)
        _ = BackupTexts.destination(for: targets[1], accounts: accounts)
        XCTAssertEqual(accounts.destinationsAsked, ["ios-applemusic"])
    }

    /// Android createPlaylistWithTracks: the create carries the first batch; the rest are appended.
    func test_should_sendTheFirstBatchWithTheCreate_when_writing() async {
        let ids = (0..<130).map { "applemusic:\(1000 + $0)" }
        let target = StubDestination(results: [:])
        let write = await PlaylistRestore.write(name: "Commute", description: nil, trackIds: ids, to: target)
        XCTAssertEqual(target.createdWith.map(\.count), [100])
        XCTAssertEqual(target.batches.map(\.count), [30])
        XCTAssertEqual(write, PlaylistWrite(created: true, added: 130, requested: 130))
    }

    // MARK: Apple Music

    /// Codex #6: an Apple Music backup keeps every entry: repeats, and a music video or unreadable entry
    /// at its place (Android `songTracks` + `unreadableEntries`).
    func test_should_keepRepeatsAndUnreadableEntries_when_backingUpAnAppleMusicPlaylist() {
        func song(_ id: String, _ name: String) -> [String: Any] {
            ["id": id, "type": "songs", "attributes": ["name": name, "artistName": "Kiri Ono", "albumName": "Harbor",
                                                        "durationInMillis": 241_000]]
        }
        let video: [String: Any] = ["id": "v1", "type": "library-music-videos",
                                    "attributes": ["name": "Live clip", "artistName": "Kiri Ono"]]
        let odd: [String: Any] = ["id": "x1", "type": "uploaded-audios", "attributes": [String: Any]()]
        let p = AMPlaylist(resourceId: "p.abc", isLibrary: true, name: "Mix", curator: nil, description: "night",
                           artwork: nil, trackCount: 5)
        let read = AppleMusicMapping.playlistRead(p, entries: [song("11", "One"), video, song("11", "One"), odd, song("12", "Two")])
        let snap = PlaylistBackups.snapshot(read)

        XCTAssertEqual(snap.id, "applemusic:library:p.abc")
        XCTAssertEqual(snap.tracks.map(\.id), ["applemusic:11", "applemusic:11", "applemusic:12"])
        XCTAssertEqual(snap.tracks.first?.durationSec, KotlinInt(int: 241))
        XCTAssertEqual(snap.unreadable, [SnapshotUnreadable(position: 1, title: "Live clip", artist: "Kiri Ono", reason: .video),
                                         SnapshotUnreadable(position: 3, title: nil, artist: nil, reason: .unavailable)])
        let content = PlaylistSnapshots.shared.content(playlist: snap)
        XCTAssertEqual(content.tracks.count, 3)
        XCTAssertEqual(content.unexported.map(\.position), [1, 3])
    }

    /// Codex #6/#12: a `next` link keeps the request's options it leaves out; the write refs as Android's.
    func test_should_carryOptionsOntoNextPagesAndReferenceSongs_when_talkingToAppleMusic() throws {
        XCTAssertEqual(AppleMusicSource.missing(["include": "catalog", "limit": "100"],
                                                in: "/v1/me/library/playlists/p.a/tracks?offset=100&limit=100"),
                       ["include": "catalog"])
        XCTAssertEqual(try AppleMusicMapping.trackRef("applemusic:1440857786"), ["id": "1440857786", "type": "songs"])
        XCTAssertEqual(try AppleMusicMapping.trackRef("applemusic:library:i.abc"), ["id": "i.abc", "type": "library-songs"])
        XCTAssertThrowsError(try AppleMusicMapping.trackRef("subsonic:42"))
        XCTAssertEqual(AppleMusicMapping.playlistAttributes(name: "Mix", description: "  ").count, 1)
        let s = AMSong(resourceId: "9", libraryId: nil, catalogId: "9", title: "Lemon", artist: "米津玄師", album: "",
                       durationMs: 255_500)
        XCTAssertEqual(AppleMusicRestoreDestination.track(s),
                       MatchTrack(id: "applemusic:9", title: "Lemon", artist: "米津玄師", album: nil,
                                  durationSec: KotlinInt(int: 255), isrc: nil))
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

    private func subsonic(_ id: String, user: String = "demo", url: String = "http://demo") -> Profile {
        Profile(id: id, service: .subsonic, baseUrl: url, username: user, createdAt: 1)
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
    var createdWith: [[String]] = []
    var batches: [[String]] = []
    var closed = false

    init(results: [String: [MatchTrack]]) { self.results = results }

    func search(_ query: String) async throws -> [MatchTrack] {
        searches.append(query)
        if let searchDelay { try await Task.sleep(for: searchDelay) }
        if searchFails { throw URLError(.timedOut) }
        return results[query] ?? []
    }

    func createPlaylist(name: String, description: String?, trackIds: [String]) async throws -> String {
        if failCreate { throw URLError(.badServerResponse) }
        created.append(name)
        createdWith.append(trackIds)
        return "new-1"
    }

    func close() { closed = true }

    func add(_ trackIds: [String], to playlistId: String) async throws {
        if let failAddAfter, batches.count >= failAddAfter { throw URLError(.timedOut) }
        batches.append(trackIds)
    }
}

/// Configured accounts with canned sources, counting what was opened and closed.
@MainActor
private final class StubAccounts: BackupAccounts {
    var profiles: [Profile]
    var activeId: String?
    var sources: [String: PlaylistBackups.Source] = [:]
    var unreadable: Set<String> = []
    var opened = 0
    var closed = 0
    var destinationsAsked: [String] = []

    init(_ profiles: [Profile], active: String? = nil) {
        self.profiles = profiles
        activeId = active ?? profiles.first?.id
    }

    func canRead(_ profileId: String) -> Bool {
        profiles.contains { $0.id == profileId } && !unreadable.contains(profileId)
    }

    func source(_ profileId: String) -> PlaylistBackups.Source? {
        guard canRead(profileId), var s = sources[profileId] else { return nil }
        opened += 1
        let previous = s.close
        s.close = { [unowned self] in previous(); self.closed += 1 }
        return s
    }

    func destination(_ profileId: String) -> RestoreDestination? {
        guard canRead(profileId) else { return nil }
        destinationsAsked.append(profileId)
        return StubDestination(results: [:])
    }
}
