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
