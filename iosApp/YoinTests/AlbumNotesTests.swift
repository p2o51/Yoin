import XCTest
@preconcurrency import YoinShared
@testable import Yoin

/// iOS notes phase 1b: album notes (Android album_notes) as stickies, and the `album_note` sync kind.
/// Fixtures/sync-album-notes.json is an Android-shaped state file (AlbumNoteSyncAdapter's projection, field for
/// field in its order; one live note with markup and a mention, one with a field this build doesn't know, a
/// tombstone, and a song note beside them).
@MainActor
final class AlbumNotesTests: XCTestCase {
    private var dir: URL!
    private var clockMs: Int64 = 1_793_000_000_000
    private var spotifyBefore: String?
    /// No account signed in: the store doesn't follow whatever account the test host app has open.
    private let unscoped = AccountScope()

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        spotifyBefore = AccountScope.shared.spotify
    }

    override func tearDown() async throws {
        AlbumNotesStore.onLocalChange = nil
        AccountScope.shared.spotify = spotifyBefore
        try? FileManager.default.removeItem(at: dir)
    }

    private func store(_ url: URL? = nil, scopes: AccountScope? = nil) -> AlbumNotesStore {
        AlbumNotesStore(fileURL: url ?? dir.appendingPathComponent("album_notes.json"),
                        now: { [unowned self] in Date(timeIntervalSince1970: Double(self.clockMs) / 1000) },
                        scopes: scopes ?? unscoped)
    }

    private func add(_ s: AlbumNotesStore, _ words: String, album: String = "al-1") -> AlbumNote? {
        s.add(provider: "apple_music", albumId: album, content: words, albumName: "Paper Moons", artist: "Mira Sol")
    }

    // MARK: Store

    func test_should_addEditDeleteAndRestoreStickies_asAndroidDoes() throws {
        let s = store()
        XCTAssertNil(add(s, "   "), "blank words write nothing")
        let first = try XCTUnwrap(add(s, "  Bought the vinyl at the harbour market \n"))
        XCTAssertEqual(first.content, "Bought the vinyl at the harbour market")
        XCTAssertEqual(first.createdAt, clockMs)
        XCTAssertEqual(first.updatedAt, clockMs)
        XCTAssertEqual(first.id, first.id.lowercased(), "a UUID as Android writes it")
        clockMs += 10
        let second = try XCTUnwrap(add(s, "Side B is for 2 a.m."))
        _ = add(s, "another album", album: "al-2")
        XCTAssertEqual(s.notes(provider: "apple_music", albumId: "al-1").map(\.id), [first.id, second.id],
                       "oldest first, this album's only")

        clockMs += 10
        let was = try XCTUnwrap(s.edit(id: first.id, content: "**Bought** it twice"))
        XCTAssertEqual(was.content, first.content, "edit hands back the note as it was")
        XCTAssertEqual(s.note(id: first.id)?.content, "**Bought** it twice")
        XCTAssertEqual(s.note(id: first.id)?.updatedAt, clockMs)
        XCTAssertEqual(s.note(id: first.id)?.createdAt, first.createdAt)

        // Cleared and saved: deleted, and Undo puts it back stamped after the delete.
        clockMs += 10
        let gone = try XCTUnwrap(s.edit(id: second.id, content: " \n "))
        XCTAssertNil(s.note(id: second.id))
        clockMs += 10
        XCTAssertTrue(s.restore(gone))
        let back = try XCTUnwrap(s.note(id: second.id))
        XCTAssertEqual(back.content, second.content)
        XCTAssertEqual(back.createdAt, second.createdAt)
        XCTAssertGreaterThan(back.updatedAt, gone.updatedAt)
        XCTAssertEqual(s.notes(provider: "apple_music", albumId: "al-1").map(\.id), [first.id, second.id])

        // On disk.
        let reloaded = store()
        XCTAssertEqual(reloaded.notes(provider: "apple_music", albumId: "al-1"),
                       s.notes(provider: "apple_music", albumId: "al-1"))
        XCTAssertNotNil(s.delete(id: first.id))
        XCTAssertNil(store().note(id: first.id))
    }

    /// Editing stays plain text but round-trips the stored markup: the same words write nothing.
    func test_should_keepAndroidMarkupByteForByte_when_theWordsDoNotChange() throws {
        let s = store()
        let markup = "# Side B\n- [ ] [@Kites in July](yoin:track/spotify:4uLU?a=Mira%20Sol)\n**late** _buses_  "
        XCTAssertTrue(s.syncPut(AlbumNote(id: "an-android", provider: "apple_music", albumId: "al-1", content: markup,
                                          createdAt: 1, updatedAt: 2, albumName: "Paper Moons", artist: "Mira Sol")))
        var local = 0
        AlbumNotesStore.onLocalChange = { local += 1 }
        // What the editor opens with (the sticky shows the trimmed text) and saves untouched.
        let sticky = ScrapStickyRules.stickies(s.notes(provider: "apple_music", albumId: "al-1"), reviewTilt: 1) { _ in 2 }
        let shown = try XCTUnwrap(sticky.first?.text)
        guard case .text(let id, let text)? = ScrapStickyRules.save(open: "an-android", text: shown) else {
            return XCTFail("an unchanged sticky saves as text")
        }
        XCTAssertNotNil(s.edit(id: id, content: text))
        XCTAssertEqual(s.note(id: "an-android")?.content, markup)
        XCTAssertEqual(s.note(id: "an-android")?.updatedAt, 2)
        XCTAssertEqual(local, 0, "nothing changed: nothing to sync")
    }

    func test_should_reportUserChanges_butNeverSyncWrites() throws {
        var local = 0
        AlbumNotesStore.onLocalChange = { local += 1 }
        let s = store()
        XCTAssertTrue(s.syncPut(AlbumNote(id: "a", provider: "apple_music", albumId: "al-1", content: "from Android",
                                          createdAt: 1, updatedAt: 2, albumName: "A", artist: "B")))
        XCTAssertTrue(s.syncRemove(id: "a"))
        XCTAssertEqual(local, 0)
        let n = try XCTUnwrap(add(s, "here"))
        s.edit(id: n.id, content: "edited")
        let gone = try XCTUnwrap(s.delete(id: n.id))
        s.restore(gone)
        XCTAssertEqual(local, 4)
    }

    func test_should_changeNothing_when_theDiskRefusesTheWrite() throws {
        // The store's folder is a file: every write fails.
        let blocker = dir.appendingPathComponent("blocked")
        try Data("x".utf8).write(to: blocker)
        let s = store(blocker.appendingPathComponent("album_notes.json"))
        XCTAssertNil(add(s, "won't land"))
        XCTAssertTrue(s.everyAccount.isEmpty)
        XCTAssertFalse(s.syncPut(AlbumNote(id: "a", provider: "apple_music", albumId: "al-1", content: "x",
                                           createdAt: 1, updatedAt: 1, albumName: "A", artist: "B")))
        XCTAssertNil(s.syncNote(id: "a"))
    }

    func test_should_keepAccountsApart_when_twoSpotifyUsersShareTheDevice() throws {
        let scopes = AccountScope()
        scopes.spotify = AccountScope.spotifyScope(userId: "ada")
        let s = store(scopes: scopes)
        let n = try XCTUnwrap(s.add(provider: "spotify", albumId: "al", content: "Ada's", albumName: "A", artist: "B"))
        XCTAssertEqual(n.scope, "spotify|ada")
        scopes.spotify = AccountScope.spotifyScope(userId: "bob")
        XCTAssertTrue(s.notes(provider: "spotify", albumId: "al").isEmpty)
        XCTAssertNil(s.note(id: n.id))
        XCTAssertNil(s.edit(id: n.id, content: "Bob can't"))
        XCTAssertEqual(s.syncNote(id: n.id)?.content, "Ada's", "sync finds it under its own account")
    }

    // MARK: Page rules

    func test_should_leanEachStickyAgainstTheOneBefore_andSaveAsAndroid() {
        let notes = ["a", "b", "c"].enumerated().map { i, id in
            AlbumNote(id: id, provider: "p", albumId: "al", content: i == 1 ? "  " : " words \(id) ",
                      createdAt: Int64(i), updatedAt: Int64(i), albumName: "A", artist: "B")
        }
        let tilts = ["albumnote": 1.5, "albumnote/c": 2.0]
        let stickies = ScrapStickyRules.stickies(notes, reviewTilt: 2.0) { tilts[$0] ?? 9 }
        XCTAssertEqual(stickies.map(\.noteId), ["a", "c"], "blank notes are left off")
        XCTAssertEqual(stickies.map(\.text), ["words a", "words c"])
        XCTAssertEqual(stickies.map(\.tilt), [-1.5, 2.0], "away from the review, then away from the one before")
        XCTAssertEqual(ScrapStickyRules.opposite(nil, 3), 3)
        XCTAssertEqual(ScrapStickyRules.opposite(-1, 3), 3)
        XCTAssertEqual(ScrapStickyRules.opposite(1, 3), -3)

        XCTAssertNil(ScrapStickyRules.save(open: ScrapStickyRules.new, text: "  "))
        XCTAssertEqual(ScrapStickyRules.save(open: ScrapStickyRules.new, text: " new \n"), .add("new"))
        XCTAssertEqual(ScrapStickyRules.save(open: "a", text: " kept as typed "), .text(noteId: "a", " kept as typed "))
        XCTAssertEqual(ScrapStickyRules.save(open: "a", text: "\n"), .delete(noteId: "a"))
    }

    // MARK: Sync (SyncDomainBridge + the shared adapter)

    nonisolated private static func clock(_ now: Int64) -> () -> KotlinLong { { KotlinLong(value: now) } }
    nonisolated private static func constant(_ text: String) -> () -> String { { text } }

    private func androidState() throws -> SyncEnvelope {
        let url = Bundle(for: Self.self).url(forResource: "sync-album-notes", withExtension: "json")
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures/sync-album-notes.json")
        return SnapshotCodec.shared.decode(bytes: SyncByteBridge.shared.toByteArray(data: try Data(contentsOf: url)))
    }

    private func albumNoteRecords(_ engine: SyncEngine, at now: Int64) async throws -> [WireRecord] {
        let files = try await engine.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1)
        return files.flatMap { SnapshotCodec.shared.decode(bytes: $0.bytes).records }
            .filter { $0.kind == "album_note" }
    }

    /// The update from carrying `album_note` opaquely to applying it: the records already in this device's
    /// replica land in the store under their own account (not the one in use), each once, and go out again
    /// exactly as they came; a sticky edited here then goes out in Android's shape.
    func test_should_applyAndroidAlbumNotesCarriedOpaquely_once_theAdapterArrives() async throws {
        let android = try androidState()
        let scope = try XCTUnwrap(android.records.first?.scope)
        let ada = SyncDomainBridge.Accounts.Spotify(profileId: "p-spotify-ada", userId: "ada-user", displayName: "Ada",
                                                    createdAt: 1)
        let bob = SyncDomainBridge.Accounts.Spotify(profileId: "p-spotify-bob", userId: "bob-user", displayName: "Bob",
                                                    createdAt: 2)
        AccountScope.shared.spotify = bob.scope
        let albumNotes = store(scopes: .shared)
        let notes = NotesStore(fileURL: dir.appendingPathComponent("notes.json"), scopes: .shared)
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"), scopes: .shared)
        let albums = AlbumMemoryStore(fileURL: dir.appendingPathComponent("albums.json"), scopes: .shared)
        let bridge = SyncDomainBridge(accounts: {
            .init(subsonic: [], appleMusic: false, storefront: nil, spotify: [ada, bob])
        }, notes: { notes }, ratings: { ratings }, albums: { albums }, albumNotes: { albumNotes })
        let binding = ActiveBinding(localProfileId: ada.profileId, syncProfileId: scope, provider: "spotify")
        let syncStore = InMemorySyncStore()
        let now: Int64 = 1_793_000_060_000

        // Before: no adapter for the kind (the build before this one).
        let without = SharedSyncAdapters.shared.create(domain: bridge, syncDb: syncStore).filter { $0.kind != "album_note" }
        let before = SyncEngine(db: syncStore, adapters: without, deviceId: "ios0000000000001",
                                deviceName: Self.constant("iPhone"), clock: Self.clock(now))
        _ = try await before.mergeRemote(
            file: RemoteFile(id: "android-state", name: "yoin-state-a1b2c3d4e5f60718.json.gz", version: 1,
                             modifiedTime: nil, size: nil, appProperties: [:]),
            envelope: android)
        _ = try await before.capture(bindings: [binding])
        _ = try await before.apply(bindings: [binding])
        XCTAssertTrue(albumNotes.everyAccount.isEmpty)
        let carried = try await albumNoteRecords(before, at: now)
        XCTAssertEqual(carried.count, 3, "two notes and a tombstone, carried as they came")

        // After: the same replica, with the adapter.
        let after = SyncEngine(db: syncStore, adapters: SharedSyncAdapters.shared.create(domain: bridge, syncDb: syncStore),
                               deviceId: "ios0000000000001", deviceName: Self.constant("iPhone"),
                               clock: Self.clock(now + 60_000))
        let applied = try await after.apply(bindings: [binding])
        XCTAssertEqual(applied.appliedLive[scope]?["album_note"]?.intValue, 2)
        XCTAssertEqual(albumNotes.everyAccount.map(\.id).sorted(), ["0f9e8d7c-an-1", "0f9e8d7c-an-2"])
        // Ada's, not Bob's (the account in use).
        XCTAssertTrue(albumNotes.notes(provider: "spotify", albumId: "6dVIqQ8qmQ5GBnJ9shOYGE").isEmpty)
        let adaNotes = AccountScope.shared.reading(asSubsonic: nil, asSpotify: ada.scope) {
            albumNotes.notes(provider: "spotify", albumId: "6dVIqQ8qmQ5GBnJ9shOYGE")
        }
        XCTAssertEqual(adaNotes.map(\.id), ["0f9e8d7c-an-1", "0f9e8d7c-an-2"])
        XCTAssertEqual(adaNotes.first?.content,
                       "# Side B\n- [ ] [@Kites in July](yoin:track/spotify:4uLU6hMCjMI75M1A2tKUQC?a=Mira%20Sol)\n- [x] **late buses**, _harbour_ lights")
        XCTAssertEqual(adaNotes.first?.createdAt, 1_792_980_000_000)
        XCTAssertEqual(adaNotes.first?.updatedAt, 1_792_990_000_000)
        XCTAssertEqual(adaNotes.first?.albumName, "Paper Moons")
        XCTAssertEqual(adaNotes.first?.artist, "Mira Sol")
        XCTAssertEqual(bridge.albumNote(id: "0f9e8d7c-an-1")?.profileId, ada.profileId)
        XCTAssertTrue(bridge.albumNotes(profileId: bob.profileId).isEmpty)

        // Nothing new to say, and nothing applied twice.
        let capture = try await after.capture(bindings: [binding])
        XCTAssertNil(capture.newVersions[scope]?["album_note"])
        XCTAssertNil(capture.tombstonesCreated[scope]?["album_note"])
        let again = try await after.apply(bindings: [binding])
        XCTAssertEqual(again.totalApplied, 0)
        XCTAssertEqual(albumNotes.everyAccount.count, 2)
        let republished = try await albumNoteRecords(after, at: now + 60_000)
        XCTAssertEqual(republished, carried, "records go out exactly as they came")

        // A sticky edited on the iPhone goes out in Android's shape, building on Android's version.
        let edited = AccountScope.shared.reading(asSubsonic: nil, asSpotify: ada.scope) {
            albumNotes.edit(id: "0f9e8d7c-an-2", content: "Bought the vinyl twice")
        }
        XCTAssertNotNil(edited)
        let local = try await after.capture(bindings: [binding])
        XCTAssertEqual(local.newVersions[scope]?["album_note"]?.intValue, 1)
        let out = try await albumNoteRecords(after, at: now + 60_000)
        let record = try XCTUnwrap(out.first { $0.key == "0f9e8d7c-an-2" })
        XCTAssertEqual(record.scope, scope)
        XCTAssertEqual(record.device, "ios0000000000001")
        XCTAssertEqual(record.prevTs?.int64Value, 1_792_991_000_000)
        XCTAssertEqual(record.prevDevice, "a1b2c3d4e5f60718")
        // The payload as text (Kotlin's toString of the record; a JsonObject doesn't cross into Swift as one).
        let text = record.description
        let start = try XCTUnwrap(text.range(of: "payload="))
        let payload = String(text[start.upperBound...].dropLast())
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any])
        // Android's seven fields, plus the field this build doesn't know, kept from Android's version.
        XCTAssertEqual(Set(json.keys), ["albumId", "provider", "content", "createdAt", "updatedAt", "albumName", "artist",
                                        "futureField"])
        XCTAssertEqual(json["futureField"] as? [String: Int], ["x": 1])
        XCTAssertEqual(json["content"] as? String, "Bought the vinyl twice")
        XCTAssertEqual((json["createdAt"] as? NSNumber)?.int64Value, 1_792_985_000_000)
    }

    /// A tombstone from Android removes the sticky here; a delete here goes out as one.
    func test_should_followDeletesBothWays() async throws {
        let ada = SyncDomainBridge.Accounts.Spotify(profileId: "p-ada", userId: "ada-user", displayName: "Ada",
                                                    createdAt: 1)
        AccountScope.shared.spotify = ada.scope
        let albumNotes = store(scopes: .shared)
        let bridge = SyncDomainBridge(accounts: {
            .init(subsonic: [], appleMusic: false, storefront: nil, spotify: [ada])
        }, albumNotes: { albumNotes })
        let scope = "s-ada"
        let binding = ActiveBinding(localProfileId: ada.profileId, syncProfileId: scope, provider: "spotify")
        let syncStore = InMemorySyncStore()
        let engine = SyncEngine(db: syncStore, adapters: [AlbumNoteSyncAdapter(domain: bridge)],
                                deviceId: "ios0000000000001", deviceName: Self.constant("iPhone"),
                                clock: Self.clock(1_793_000_000_000))
        let keep = try XCTUnwrap(albumNotes.add(provider: "spotify", albumId: "al", content: "keep", albumName: "A",
                                                artist: "B"))
        let drop = try XCTUnwrap(albumNotes.add(provider: "spotify", albumId: "al", content: "drop", albumName: "A",
                                                artist: "B"))
        let gone = try XCTUnwrap(albumNotes.add(provider: "spotify", albumId: "al", content: "gone", albumName: "A",
                                                artist: "B"))
        _ = try await engine.capture(bindings: [binding])
        for file in try await engine.outgoingFiles(epoch: 0, writtenAt: 1, appVersionCode: 1) {
            try await engine.markUploaded(file: file)
        }

        // Android deletes one.
        let tombstone = WireRecord(kind: "album_note", kindVersion: 1, scope: scope, key: drop.id,
                                   ts: 1_793_000_100_000, device: "a1b2c3d4e5f60718",
                                   prevTs: KotlinLong(value: drop.updatedAt), prevDevice: "ios0000000000001",
                                   deleted: true, payload: nil)
        _ = try await engine.mergeRemote(
            file: RemoteFile(id: "android", name: "yoin-state-a1b2c3d4e5f60718.json.gz", version: 2, modifiedTime: nil,
                             size: nil, appProperties: [:]),
            envelope: SyncEnvelope(format: "yoin-sync", schema: 1, epoch: 0, deviceId: "a1b2c3d4e5f60718",
                                   deviceName: "Pixel 9", fileClass: "state", shard: 0, writtenAt: 1,
                                   appVersionCode: 31, records: [tombstone], purges: nil))
        let applied = try await engine.apply(bindings: [binding])
        XCTAssertEqual(applied.removed[scope]?["album_note"]?.intValue, 1)
        XCTAssertNil(albumNotes.note(id: drop.id))
        XCTAssertNotNil(albumNotes.note(id: keep.id))

        // The iPhone deletes one (a single delete: under the mass-delete valve).
        XCTAssertNotNil(albumNotes.delete(id: gone.id))
        let capture = try await engine.capture(bindings: [binding])
        XCTAssertEqual(capture.tombstonesCreated[scope]?["album_note"]?.intValue, 1)
        let out = try await albumNoteRecords(engine, at: 2)
        XCTAssertEqual(out.first { $0.key == gone.id }?.deleted, true)
        XCTAssertTrue(out.first { $0.key == gone.id }?.description.hasSuffix("payload=null)") == true)
        XCTAssertEqual(out.first { $0.key == keep.id }?.deleted, false)
    }
}
