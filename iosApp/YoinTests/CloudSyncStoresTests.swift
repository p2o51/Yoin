import XCTest
import YoinShared
@testable import Yoin

/// The Swift side of cloud sync: store changes from sync never count as local edits, ratings keep
/// their change time, and the shared golden fixture (Android's own files) is readable from Swift.
@MainActor
final class CloudSyncStoresTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        LocalRatings.onLocalChange = nil
        NotesStore.onLocalChange = nil
        try? FileManager.default.removeItem(at: dir)
    }

    func test_should_buildBunFromLocalStores_when_serverHasNoRatings() {
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"))
        let memories = AlbumMemoryStore(fileURL: dir.appendingPathComponent("album-memories.json"))
        let tracks = (1...4).map { Track(id: MediaId(provider: "subsonic", rawId: "t\($0)"), number: $0, title: "T\($0)",
                                         durationSec: 200, rating: 2) }
        let album = Album(id: MediaId(provider: "subsonic", rawId: "al"), title: "A", artist: "B", year: 2020,
                          palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: tracks, rating: 4)
        // Nothing here: the server's stars never count (Android) → Unrated.
        XCTAssertEqual(AlbumBun.of(album, ratings: ratings, memories: memories).kind, .unrated)
        ratings.syncPut(8, provider: "subsonic", songId: "t1", updatedAt: 1)
        ratings.syncPut(9, provider: "subsonic", songId: "t3", updatedAt: 1)
        var bun = AlbumBun.of(album, ratings: ratings, memories: memories)
        XCTAssertEqual(bun.kind, .average)
        XCTAssertEqual(bun.score, 8.5)
        XCTAssertEqual(bun.rated, [true, false, true, false])
        XCTAssertEqual(bun.ratedCount, 2)
        memories.putAlbum(.init(provider: "subsonic", albumId: "al", rating: 8.7, review: nil, reviewUpdatedAt: nil,
                                updatedAt: 1))
        bun = AlbumBun.of(album, ratings: ratings, memories: memories)
        XCTAssertEqual(bun.kind, .album)
        XCTAssertEqual(bun.score, 8.7)
    }

    func test_should_showNoLabel_when_songIsUnrated() {
        XCTAssertNil(RatingBar.label(nil))
        XCTAssertEqual(RatingBar.label(0), "0.0")
        XCTAssertEqual(RatingBar.label(7.4), "7.4")
        XCTAssertEqual(RatingBar.label(10), "10")
    }

    /// 51, 2026-11-04: nil (never rated) and 0 (rated at the bottom) are different everywhere a rating shows.
    func test_should_keepZeroApartFromUnrated_when_storedAndReloaded() {
        let url = dir.appendingPathComponent("ratings.json")
        let ratings = LocalRatings(fileURL: url)
        XCTAssertNil(ratings.rating(provider: "subsonic", songId: "t1"))
        ratings.set(0, provider: "subsonic", songId: "t1")
        XCTAssertEqual(LocalRatings(fileURL: url).rating(provider: "subsonic", songId: "t1"), 0)
        XCTAssertNil(LocalRatings(fileURL: url).rating(provider: "subsonic", songId: "t2"))

        let memories = AlbumMemoryStore(fileURL: dir.appendingPathComponent("album-memories.json"))
        let tracks = (1...2).map { Track(id: MediaId(provider: "subsonic", rawId: "t\($0)"), number: $0, title: "T\($0)",
                                         durationSec: 200) }
        let album = Album(id: MediaId(provider: "subsonic", rawId: "al"), title: "A", artist: "B", year: 2020,
                          palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: tracks)
        let bun = AlbumBun.of(album, ratings: ratings, memories: memories)
        XCTAssertEqual(bun.rated, [true, false])
        XCTAssertEqual(bun.kind, .average)
        XCTAssertEqual(bun.score, 0)
        XCTAssertEqual(HomeHistory.scoreText(0), "0.0")
        XCTAssertEqual(HomeHistory.scoreText(nil), "N/A")
    }

    /// Android 557a58681: a 0 saved before the zero mark existed reads as not rated; sync's nil keeps a row.
    func test_should_readLegacyZeroAsUnrated_when_noZeroMarkExists() throws {
        let url = dir.appendingPathComponent("ratings.json")
        try JSONEncoder().encode(["subsonic:s1": 0.0, "subsonic:s2": 4.2]).write(to: url)
        let store = LocalRatings(fileURL: url)
        XCTAssertNil(store.rating(provider: "subsonic", songId: "s1"))
        XCTAssertEqual(store.rating(provider: "subsonic", songId: "s2"), 4.2)
        store.syncPut(0, provider: "subsonic", songId: "s3", updatedAt: 5)
        store.syncPut(nil, provider: "subsonic", songId: "s2", updatedAt: 6)
        let reloaded = LocalRatings(fileURL: url)
        XCTAssertEqual(reloaded.rating(provider: "subsonic", songId: "s3"), 0)
        XCTAssertNil(reloaded.rating(provider: "subsonic", songId: "s2"))
        XCTAssertTrue(reloaded.hasRow(key: "subsonic:s2"))
        XCTAssertEqual(reloaded.updatedAt["subsonic:s2"], 6)
    }

    /// The scrapbook's Clear: back to nil (not 0), counted as a local edit for sync.
    func test_should_forgetRating_when_cleared() {
        var local = 0
        LocalRatings.onLocalChange = { local += 1 }
        let url = dir.appendingPathComponent("ratings.json")
        let ratings = LocalRatings(fileURL: url)
        ratings.set(6.4, provider: "subsonic", songId: "s1")
        ratings.clear(provider: "subsonic", songId: "s1")
        XCTAssertNil(ratings.rating(provider: "subsonic", songId: "s1"))
        // The row stays (sync carries the clear as a bare `rating: 0`), with a new change time.
        XCTAssertTrue(ratings.hasRow(key: "subsonic:s1"))
        XCTAssertNotNil(ratings.updatedAt["subsonic:s1"])
        XCTAssertNil(LocalRatings(fileURL: url).rating(provider: "subsonic", songId: "s1"))
        XCTAssertEqual(local, 2)
        ratings.clear(provider: "subsonic", songId: "s1")
        XCTAssertEqual(local, 2, "nothing to clear: no edit")
    }

    func test_should_readLegacyRatings_when_noTimesFileExists() throws {
        let url = dir.appendingPathComponent("ratings.json")
        try JSONEncoder().encode(["subsonic:s1": 7.5]).write(to: url)
        let store = LocalRatings(fileURL: url)
        XCTAssertEqual(store.rating(provider: "subsonic", songId: "s1"), 7.5)
        XCTAssertNil(store.updatedAt["subsonic:s1"])
    }

    func test_should_notReportLocalChange_when_syncWrites() {
        var local = 0
        LocalRatings.onLocalChange = { local += 1 }
        NotesStore.onLocalChange = { local += 1 }
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"))
        let notes = NotesStore(fileURL: dir.appendingPathComponent("notes.json"))

        ratings.syncPut(9.3, provider: "subsonic", songId: "s1", updatedAt: 42)
        notes.syncPut(SongNote(id: "n1", provider: "subsonic", trackId: "s1", content: "from Android", createdAt: 1,
                               updatedAt: 2, title: "T", artist: "A", positionMs: 74_000))
        XCTAssertEqual(local, 0)
        XCTAssertEqual(LocalRatings(fileURL: dir.appendingPathComponent("ratings.json")).updatedAt["subsonic:s1"], 42)

        ratings.set(8, provider: "subsonic", songId: "s2")
        notes.update(id: "n1", content: "edited here")
        XCTAssertEqual(local, 2)
    }

    func test_should_decodeAndroidGoldenFiles_when_readFromSwift() throws {
        let url = Bundle(for: Self.self).url(forResource: "sync-golden", withExtension: "json")
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/sync-golden.json")
        let golden = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let files = golden["files"] as! [[String: Any]]
        XCTAssertFalse(files.isEmpty)
        for file in files {
            let json = file["json"] as! String
            let bytes = KotlinByteArray(size: Int32(json.utf8.count))
            for (i, b) in json.utf8.enumerated() { bytes.set(index: Int32(i), value: Int8(bitPattern: b)) }
            let envelope = SnapshotCodec.shared.decode(bytes: bytes)
            XCTAssertEqual(envelope.deviceName, "Pixel Tablet")
            XCTAssertEqual(Int(envelope.records.count), file["recordCount"] as? Int)
        }
    }

    // MARK: Codex G3

    /// A directory where the store's file should be: every write fails.
    private func unwritable(_ name: String) throws -> URL {
        let url = dir.appendingPathComponent(name)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// C15: a sync write that can't reach the disk changes nothing and says so (sync retries it).
    func test_should_changeNothing_when_aSyncWriteCannotPersist() throws {
        let notes = NotesStore(fileURL: try unwritable("notes.json"))
        XCTAssertFalse(notes.syncPut(SongNote(id: "n1", provider: "subsonic", trackId: "t", content: "x",
                                              createdAt: 1, updatedAt: 1, title: "T", artist: "A", positionMs: nil)))
        XCTAssertNil(notes.note(id: "n1"))

        let memories = AlbumMemoryStore(fileURL: try unwritable("album-memories.json"))
        XCTAssertFalse(memories.putTitle(.init(provider: "subsonic", albumId: "al", title: "Mine", updatedAt: 1)))
        XCTAssertNil(memories.userTitle(provider: "subsonic", albumId: "al"))

        // ratings-v2.json sits beside the file named: make that the directory.
        let ratingsDir = dir.appendingPathComponent("r", isDirectory: true)
        try FileManager.default.createDirectory(at: ratingsDir.appendingPathComponent("ratings-v2.json"),
                                                withIntermediateDirectories: true)
        let ratings = LocalRatings(fileURL: ratingsDir.appendingPathComponent("ratings.json"))
        XCTAssertFalse(ratings.syncPut(7, provider: "subsonic", songId: "s1", updatedAt: 3))
        XCTAssertNil(ratings.rating(provider: "subsonic", songId: "s1"))
        // A user edit still shows for this run.
        ratings.set(5, provider: "subsonic", songId: "s2")
        XCTAssertEqual(ratings.rating(provider: "subsonic", songId: "s2"), 5)
    }

    /// C18: value, zero mark and time are one document; the three legacy files migrate together.
    func test_should_keepValueZeroAndTimeTogether_when_migratingLegacyFiles() throws {
        let url = dir.appendingPathComponent("ratings.json")
        try JSONEncoder().encode(["subsonic:a": 0.0, "subsonic:b": 0.0, "subsonic:c": 6.5]).write(to: url)
        try JSONEncoder().encode(["subsonic:a": Int64(10), "subsonic:b": 20, "subsonic:c": 30])
            .write(to: dir.appendingPathComponent("ratings-times.json"))
        try JSONEncoder().encode(Set(["subsonic:a"])).write(to: dir.appendingPathComponent("ratings-zero.json"))
        let migrated = LocalRatings(fileURL: url)
        XCTAssertEqual(migrated.rating(provider: "subsonic", songId: "a"), 0)
        XCTAssertNil(migrated.rating(provider: "subsonic", songId: "b"))
        XCTAssertTrue(migrated.hasRow(key: "subsonic:b"))
        XCTAssertEqual(migrated.updatedAt["subsonic:c"], 30)
        // The document alone carries everything: the legacy files no longer matter.
        for name in ["ratings.json", "ratings-times.json", "ratings-zero.json"] {
            try FileManager.default.removeItem(at: dir.appendingPathComponent(name))
        }
        let reloaded = LocalRatings(fileURL: url)
        XCTAssertEqual(reloaded.rating(provider: "subsonic", songId: "a"), 0)
        XCTAssertNil(reloaded.rating(provider: "subsonic", songId: "b"))
        XCTAssertEqual(reloaded.updatedAt["subsonic:a"], 10)
        reloaded.clear(provider: "subsonic", songId: "a")
        let doc = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("ratings-v2.json")))
            as? [String: Any]
        XCTAssertEqual(doc?["version"] as? Int, 2)
        XCTAssertNil(LocalRatings(fileURL: url).rating(provider: "subsonic", songId: "a"))
    }

    /// C03: another server's notes, ratings and album memories never show under this one.
    func test_should_keepAccountsApart_when_serverIsReplaced() {
        let scopes = AccountScope()
        scopes.adoptsLegacyRows = false
        scopes.subsonic = "https://a|me"
        let notes = NotesStore(fileURL: dir.appendingPathComponent("notes.json"), scopes: scopes)
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"), scopes: scopes)
        let memories = AlbumMemoryStore(fileURL: dir.appendingPathComponent("album-memories.json"), scopes: scopes)
        notes.add(provider: "subsonic", trackId: "t1", content: "on A", positionMs: nil, title: "T", artist: "X")
        ratings.set(9, provider: "subsonic", songId: "t1")
        memories.putTitle(.init(provider: "subsonic", albumId: "al", title: "A's", updatedAt: 1))
        notes.add(provider: "applemusic", trackId: "m1", content: "apple", positionMs: nil, title: "T", artist: "X")

        scopes.subsonic = "https://b|me"
        XCTAssertTrue(notes.notes(provider: "subsonic", trackId: "t1").isEmpty)
        XCTAssertNil(ratings.rating(provider: "subsonic", songId: "t1"))
        XCTAssertTrue(ratings.ratings.isEmpty)
        XCTAssertNil(memories.userTitle(provider: "subsonic", albumId: "al"))
        XCTAssertEqual(notes.notes.map(\.content), ["apple"], "one-account providers aren't scoped")
        ratings.set(2, provider: "subsonic", songId: "t1")

        scopes.subsonic = "https://a|me"
        XCTAssertEqual(notes.notes(provider: "subsonic", trackId: "t1").map(\.content), ["on A"])
        XCTAssertEqual(ratings.rating(provider: "subsonic", songId: "t1"), 9)
        XCTAssertEqual(memories.userTitle(provider: "subsonic", albumId: "al"), "A's")
        // And on disk.
        XCTAssertEqual(LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"), scopes: scopes)
            .rating(provider: "subsonic", songId: "t1"), 9)
    }

    /// C03 migration: rows from before scopes belong to the first account seen, once.
    func test_should_adoptLegacyRows_into_theFirstAccount() throws {
        let url = dir.appendingPathComponent("notes.json")
        let legacy = SongNote(id: "n0", provider: "subsonic", trackId: "t1", content: "old", createdAt: 1, updatedAt: 1,
                              title: "T", artist: "X", positionMs: nil)
        try JSONEncoder().encode([legacy]).write(to: url)
        let scopes = AccountScope()
        scopes.adoptsLegacyRows = false
        let notes = NotesStore(fileURL: url, scopes: scopes)
        notes.adoptLegacy(provider: "subsonic", scope: "https://a|me")
        scopes.subsonic = "https://a|me"
        XCTAssertEqual(notes.notes.map(\.id), ["n0"])
        scopes.subsonic = "https://b|me"
        XCTAssertTrue(notes.notes.isEmpty)
    }
}
