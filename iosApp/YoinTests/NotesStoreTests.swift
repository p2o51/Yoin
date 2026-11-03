import XCTest
@testable import Yoin

@MainActor
final class NotesStoreTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func note(_ id: String, at: Int64?, created: Int64) -> SongNote {
        SongNote(id: id, provider: "subsonic", trackId: "s1", content: id, createdAt: created, updatedAt: created,
                 title: "T", artist: "A", positionMs: at)
    }

    func test_should_persistNotesPerSong_when_reloaded() {
        let url = dir.appendingPathComponent("notes.json")
        var clock = Date(timeIntervalSince1970: 100)
        let store = NotesStore(fileURL: url, now: { clock })
        store.add(provider: "subsonic", trackId: "s1", content: "Chorus comes in", positionMs: 83_000, title: "T", artist: "A")
        clock = Date(timeIntervalSince1970: 200)
        store.add(provider: "subsonic", trackId: "s1", content: "Bass walks up", positionMs: 12_000, title: "T", artist: "A")
        store.add(provider: "subsonic", trackId: "s2", content: "Other song", positionMs: 1_000, title: "U", artist: "A")
        store.add(provider: "spotify", trackId: "s1", content: "Same id, other provider", positionMs: 0, title: "T", artist: "A")

        let reloaded = NotesStore(fileURL: url)
        let s1 = reloaded.notes(provider: "subsonic", trackId: "s1")
        XCTAssertEqual(s1.map(\.content), ["Bass walks up", "Chorus comes in"])
        XCTAssertEqual(s1.map(\.positionMs), [12_000, 83_000])
        XCTAssertEqual(s1.last?.createdAt, 100_000)
        XCTAssertEqual(reloaded.notes(provider: "spotify", trackId: "s1").count, 1)
        XCTAssertEqual(reloaded.notes.count, 4)
    }

    func test_should_ignoreBlankWords_when_saving() {
        let store = NotesStore(fileURL: dir.appendingPathComponent("notes.json"))
        XCTAssertNil(store.add(provider: "subsonic", trackId: "s1", content: "  \n ", positionMs: 0, title: "T", artist: "A"))
        let saved = store.add(provider: "subsonic", trackId: "s1", content: "  trimmed \n", positionMs: -5, title: "T", artist: "A")
        XCTAssertEqual(saved?.content, "trimmed")
        XCTAssertEqual(saved?.positionMs, 0)
    }

    func test_should_removeNote_when_deleted() {
        let url = dir.appendingPathComponent("notes.json")
        let store = NotesStore(fileURL: url)
        let n = store.add(provider: "subsonic", trackId: "s1", content: "x", positionMs: 0, title: "T", artist: "A")!
        store.delete(id: n.id)
        XCTAssertTrue(NotesStore(fileURL: url).notes.isEmpty)
    }

    func test_should_sortByMomentThenCreated_withUnanchoredLast_when_timeline() {
        let sorted = NotesStore.timeline([
            note("legacy", at: nil, created: 1),
            note("late", at: 90_000, created: 2),
            note("early-second", at: 10_000, created: 5),
            note("early-first", at: 10_000, created: 3),
        ])
        XCTAssertEqual(sorted.map(\.id), ["early-first", "early-second", "late", "legacy"])
    }

    func test_should_pickLatestAnchorAtOrBeforePlayhead_when_resolvingCurrentNote() {
        let notes = [note("a", at: 12_000, created: 1), note("b", at: 48_000, created: 2),
                     note("c", at: 83_000, created: 3), note("d", at: nil, created: 4)]
        XCTAssertNil(NotesStore.currentAnchoredNoteId(notes, positionMs: 11_999))
        XCTAssertEqual(NotesStore.currentAnchoredNoteId(notes, positionMs: 12_000), "a")
        XCTAssertEqual(NotesStore.currentAnchoredNoteId(notes, positionMs: 50_000), "b")
        XCTAssertEqual(NotesStore.currentAnchoredNoteId(notes.reversed(), positionMs: 500_000), "c")
    }

    func test_should_formatMinutesAndSeconds_when_formattingMoment() {
        XCTAssertEqual(NotesStore.formatPosition(0), "0:00")
        XCTAssertEqual(NotesStore.formatPosition(83_999), "1:23")
        XCTAssertEqual(NotesStore.formatPosition(-10), "0:00")
        XCTAssertEqual(NotesStore.formatPosition(3_600_000), "60:00")
    }
}

@MainActor
final class RatingScaleTests: XCTestCase {
    func test_should_halveAndRoundHalfUp_when_mappingToSubsonicStars() {
        // Android YoinRepository.storeRating: (rating / 2).roundToInt().coerceIn(0, 5)
        XCTAssertEqual(RatingScale.serverRating(local: 0), 0)
        XCTAssertEqual(RatingScale.serverRating(local: 0.9), 0)
        XCTAssertEqual(RatingScale.serverRating(local: 1.0), 1)
        XCTAssertEqual(RatingScale.serverRating(local: 6.3), 3)
        XCTAssertEqual(RatingScale.serverRating(local: 7.0), 4)
        XCTAssertEqual(RatingScale.serverRating(local: 8.9), 4)
        XCTAssertEqual(RatingScale.serverRating(local: 9.0), 5)
        XCTAssertEqual(RatingScale.serverRating(local: 10), 5)
        XCTAssertEqual(RatingScale.serverRating(local: 14), 5)
        XCTAssertEqual(RatingScale.serverRating(local: -3), 0)
    }

    func test_should_doubleStars_when_seedingFromServer() {
        XCTAssertNil(RatingScale.local(fromServer: nil))
        XCTAssertNil(RatingScale.local(fromServer: 0))
        XCTAssertEqual(RatingScale.local(fromServer: 3), 6)
        XCTAssertEqual(RatingScale.local(fromServer: 9), 10)
    }

    func test_should_persistLocalRating_when_reloaded() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathComponent("ratings.json")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let ratings = LocalRatings(fileURL: url)
        ratings.set(7.4, provider: "subsonic", songId: "s1")
        ratings.set(12, provider: "subsonic", songId: "s2")
        let reloaded = LocalRatings(fileURL: url)
        XCTAssertEqual(reloaded.rating(provider: "subsonic", songId: "s1"), 7.4)
        XCTAssertEqual(reloaded.rating(provider: "subsonic", songId: "s2"), 10)
        XCTAssertNil(reloaded.rating(provider: "spotify", songId: "s1"))
    }

    func test_should_preferLocalThenServerStars_when_readingNowPlayingRating() async throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathComponent("ratings.json")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let data = NowPlayingData(ratings: LocalRatings(fileURL: url))
        let track = PlayableTrack(id: "s1", provider: "subsonic", title: "T", artist: "A", album: "Al", albumId: nil,
                                  durationSec: 200, streamURL: URL(string: "http://x/s1")!, coverURL: nil,
                                  starred: true, userRating: 3)
        XCTAssertEqual(data.rating(track), 0, "nothing known before prepare")
        await data.prepare(track, client: nil)
        XCTAssertEqual(data.rating(track), 6)
        XCTAssertTrue(data.isFavorite(track))
        data.dragRating(8.2, track)
        XCTAssertEqual(data.rating(track), 8.2)
        data.commitRating(track, client: nil)
        XCTAssertEqual(data.rating(track), 8.2)
        XCTAssertEqual(LocalRatings(fileURL: url).rating(provider: "subsonic", songId: "s1"), 8.2)
        data.toggleFavorite(track, client: nil)
        XCTAssertFalse(data.isFavorite(track))
    }
}
