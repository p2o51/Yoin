import SwiftUI
import XCTest
@testable import Yoin

/// An album's one note (Android album_notes, legacy rows: the latest non-blank by updatedAt), read by
/// the scrapbook, the Memories diary and the Notes counts. Android doesn't write the table any more
/// and keeps it out of Drive sync, so neither does iOS.
@MainActor
final class AlbumNoteStoreTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func store(_ url: URL? = nil, clock: @escaping () -> Date = Date.init) -> AlbumNoteStore {
        AlbumNoteStore(fileURL: url, now: clock)
    }

    @discardableResult
    private func add(_ s: AlbumNoteStore, _ text: String, album: String = "al1", provider: String = "subsonic") -> AlbumNote? {
        s.add(provider: provider, albumId: album, albumName: "Album", artist: "Artist", content: text)
    }

    func test_should_showLatestNonBlankRow_when_albumHasSeveral() {
        var t = 100.0
        let s = store(clock: { Date(timeIntervalSince1970: t) })
        let first = add(s, "first")
        t = 200
        let second = add(s, "second")
        XCTAssertEqual(s.latest(provider: "subsonic", albumId: "al1")?.id, second?.id)
        // An edit makes the older row the newest again.
        t = 300
        s.update(id: first!.id, content: "  first, again  ")
        XCTAssertEqual(s.text(provider: "subsonic", albumId: "al1"), "first, again")
        XCTAssertEqual(s.notes(provider: "subsonic", albumId: "al1").map(\.content), ["first, again", "second"])
    }

    func test_should_keepEarlierRow_when_updatedAtTies() {
        let s = store(clock: { Date(timeIntervalSince1970: 100) })
        let a = add(s, "created first")
        add(s, "created second")
        XCTAssertEqual(s.latest(provider: "subsonic", albumId: "al1")?.id, a?.id)
    }

    func test_should_ignoreBlankWords_and_dropOnBlankEdit() {
        let s = store()
        XCTAssertNil(add(s, "  \n "))
        let n = add(s, "keep")
        XCTAssertNil(s.update(id: n!.id, content: " "))
        XCTAssertNil(s.latest(provider: "subsonic", albumId: "al1"))
        XCTAssertTrue(s.counts(provider: "subsonic").isEmpty)
    }

    func test_should_countNonBlankRowsPerAlbumAndProvider() {
        let s = store()
        add(s, "a1", album: "al1")
        add(s, "a2", album: "al1")
        add(s, "b1", album: "al2")
        add(s, "other service", album: "al1", provider: "spotify")
        XCTAssertEqual(s.counts(provider: "subsonic"), ["al1": 2, "al2": 1])
        XCTAssertEqual(s.counts(provider: "spotify"), ["al1": 1])
        XCTAssertNil(s.text(provider: "spotify", albumId: "al2"))
    }

    func test_should_persistAndDelete_when_reloaded() {
        let url = dir.appendingPathComponent("album-notes.json")
        let s = store(url)
        let n = add(s, "On the train")
        add(s, "Other album", album: "al2")
        XCTAssertEqual(store(url).text(provider: "subsonic", albumId: "al1"), "On the train")
        s.delete(id: n!.id)
        XCTAssertNil(store(url).text(provider: "subsonic", albumId: "al1"))
        XCTAssertEqual(store(url).counts(provider: "subsonic"), ["al2": 1])
    }

    // MARK: Memories

    private func inputs(counts: [String: Int], plays: [PlayEvent] = []) -> HomeHistory.Inputs {
        let album = Album(id: MediaId(provider: "subsonic", rawId: "al1"), title: "Album", artist: "Artist", year: 2020,
                          palette: CoverPalette(a: .red, b: .green, c: .blue),
                          tracks: (1...6).map { Track(id: MediaId(provider: "subsonic", rawId: "t\($0)"), number: $0,
                                                       title: "T\($0)", durationSec: 200) })
        return HomeHistory.Inputs(now: 1_800_000_000_000, plays: plays, notes: [], details: ["al1": album], known: [:],
                                  localRating: { _ in nil }, resolve: { _, _ in nil }, albumNoteCounts: counts)
    }

    func test_should_seedAMemoryAndCountTheNote_when_onlyAnAlbumNoteExists() {
        let none = HomeHistory.memories(inputs(counts: [:]))
        XCTAssertTrue(none.isEmpty)
        let one = HomeHistory.memories(inputs(counts: ["al1": 1]))
        XCTAssertEqual(one.map(\.albumId), ["al1"])
        XCTAssertEqual(one[0].noteCount, 1)
        // The album note never moves "last written" (Android: nothing writes it).
        XCTAssertNil(one[0].lastWrittenAt)
        XCTAssertFalse(one[0].isMemoryEligible, "one note is under the gate")
        XCTAssertTrue(HomeHistory.memories(inputs(counts: ["al1": 2]))[0].isMemoryEligible)
        XCTAssertTrue(HomeHistory.hasMemory(one[0]))
    }

    func test_should_leadTheDiaryWithTheAlbumNote_when_buildingACard() {
        let memory = HomeHistory.memories(inputs(counts: ["al1": 2]))[0]
        let withNote = MemoryDeck.card(memory, MemoryDeck.Inputs(memories: [memory], albumNote: { $0 == "al1" ? "Bought on a rainy Tuesday" : nil }))
        XCTAssertEqual(withNote.albumNote, "Bought on a rainy Tuesday")
        XCTAssertEqual(withNote.noteCount, 2)
        let without = MemoryDeck.card(memory, MemoryDeck.Inputs(memories: [memory]))
        XCTAssertNil(without.albumNote)
        XCTAssertTrue(without.diaryTracks.isEmpty)
    }
}
