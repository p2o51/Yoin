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

    /// The scrapbook's Clear: back to nil (not 0), counted as a local edit for sync.
    func test_should_forgetRating_when_cleared() {
        var local = 0
        LocalRatings.onLocalChange = { local += 1 }
        let url = dir.appendingPathComponent("ratings.json")
        let ratings = LocalRatings(fileURL: url)
        ratings.set(6.4, provider: "subsonic", songId: "s1")
        ratings.clear(provider: "subsonic", songId: "s1")
        XCTAssertNil(ratings.rating(provider: "subsonic", songId: "s1"))
        XCTAssertNil(ratings.updatedAt["subsonic:s1"])
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
}
