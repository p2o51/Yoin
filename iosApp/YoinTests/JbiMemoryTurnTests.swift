import XCTest
@testable import Yoin

/// Jump Back In's memory 1×2 taking turns (Android JbiMemoryTurn, pickJbiMemoryCandidates and jbiDeck;
/// the picker cases mirror HomeJbiRichShelfTest and HomeMemoryPillTest).
@MainActor
final class JbiMemoryTurnTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "jbi-memory-turn-tests-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults.standard.removePersistentDomain(forName: name) }
        return UserDefaults(suiteName: name)!
    }

    private func memory(_ id: String, rating: Double? = 8, written: Int64? = nil, played: Int64? = nil,
                        notes: Int = 0, eligible: Bool = true) -> AlbumMemory {
        let album = Album(id: MediaId(provider: "subsonic", rawId: id), title: "Album \(id)", artist: "Artist",
                          year: 2020, palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
        return AlbumMemory(album: album, totalTracks: 10, ratedTrackCount: 0, coverage: 0, averageSongRating: nil,
                           albumRating: rating, noteCount: notes, lastWrittenAt: written, playCount: 1,
                           firstPlayedAt: nil, lastPlayedAt: played, isMemoryEligible: eligible)
    }

    private func pick(_ c: [AlbumMemory], avoid: String?, prefer: String? = nil, turn: Int64 = 0,
                      playedBefore: Int64? = nil) -> String? {
        HomeHistory.pickJbiMemories(c, avoid: avoid, prefer: prefer, count: 1, turn: turn,
                                    recentlyPlayedBefore: playedBefore).first?.albumId
    }

    // MARK: The turn

    func test_should_takeTheNextTurn_when_theAppStarts() {
        let d = defaults()
        XCTAssertEqual(JbiMemoryTurn(defaults: d).turn, 1)
        // Kept on this device: the next start is the next turn.
        XCTAssertEqual(JbiMemoryTurn(defaults: d).turn, 2)
    }

    func test_should_keepTheTurn_when_backSoonerThanHalfAnHour() {
        var now: Int64 = 1_000_000
        let t = JbiMemoryTurn(defaults: defaults(), clock: { now })
        t.didEnterBackground()
        now += JbiMemoryTurn.awayMs - 1
        t.willEnterForeground()
        XCTAssertEqual(t.turn, 1)
        // Coming to the front without having left changes nothing either.
        now += 2 * JbiMemoryTurn.awayMs
        t.willEnterForeground()
        XCTAssertEqual(t.turn, 1)
    }

    func test_should_takeTheNextTurn_when_backAfterHalfAnHour() {
        var now: Int64 = 1_000_000
        let d = defaults()
        let t = JbiMemoryTurn(defaults: d, clock: { now })
        t.didEnterBackground()
        now += JbiMemoryTurn.awayMs
        t.willEnterForeground()
        XCTAssertEqual(t.turn, 2)
        t.didEnterBackground()
        now += 3 * JbiMemoryTurn.awayMs
        t.willEnterForeground()
        XCTAssertEqual(t.turn, 3)
        XCTAssertEqual(JbiMemoryTurn(defaults: d).turn, 4)
    }

    // MARK: The deck

    func test_should_shuffleAsAndroidDoes_when_dealingTheDeck() {
        // jbiDeck on Android (Kotlin, same pools and turns).
        let pool = ["a", "b", "c", "e"].map { memory($0) }
        func deck(_ turn: Int64) -> [String] { HomeHistory.jbiDeck(pool, turn: turn).map(\.albumId) }
        XCTAssertEqual(deck(0), ["b", "e", "a", "c"])
        XCTAssertEqual(deck(3), ["b", "e", "a", "c"])
        XCTAssertEqual(deck(4), ["b", "c", "a", "e"])
        XCTAssertEqual(deck(13), ["c", "a", "e", "b"])
        XCTAssertEqual(deck(-1), ["c", "e", "a", "b"])
        XCTAssertEqual(deck(20_000), ["c", "e", "a", "b"])
        let ids = ["al-1", "al-10", "al-2", "al-3", "al-4", "al-5", "al-6"]
        let longer = ids.map { memory($0) }
        XCTAssertEqual(HomeHistory.jbiDeck(longer, turn: 0).map(\.albumId), ["al-4", "al-1", "al-5", "al-6", "al-10", "al-3", "al-2"])
        XCTAssertEqual(HomeHistory.jbiDeck(longer, turn: 7).map(\.albumId), ["al-3", "al-5", "al-1", "al-10", "al-4", "al-6", "al-2"])
        XCTAssertEqual(HomeHistory.jbiDeck(longer, turn: 20_000).map(\.albumId), ["al-5", "al-4", "al-1", "al-2", "al-3", "al-6", "al-10"])
        // Dealt from the turn's place in its round, whatever order the memories came in.
        XCTAssertEqual((0..<8).map { pick(pool.reversed(), avoid: nil, turn: Int64($0)) }, ["b", "e", "a", "c", "b", "c", "a", "e"])
    }

    // MARK: The picker

    func test_should_dealDistinctMemoriesIntoTheSlots_when_pickingSeveralMemories() {
        let c = [memory("a"), memory("b"), memory("c"), memory("d"), memory("e"), memory("latest")]
        let picks = HomeHistory.pickJbiMemories(c, avoid: "latest", prefer: nil, count: 4, turn: 1, recentlyPlayedBefore: nil)
        XCTAssertEqual(picks.count, 4)
        XCTAssertEqual(Set(picks.map(\.albumId)).count, 4)
        XCTAssertFalse(picks.contains { $0.albumId == "latest" })
        XCTAssertEqual(picks.first?.albumId, pick(c, avoid: "latest", turn: 1))
    }

    func test_should_takeTurnsEachOpening_when_severalMemoriesQualify() {
        let c = [memory("a"), memory("b"), memory("c"), memory("e"), memory("latest")]
        // One round: each memory leads once; never the header's latest.
        XCTAssertEqual(Set((0...3).map { pick(c, avoid: "latest", turn: Int64($0)) }), ["a", "b", "c", "e"])
        // The same within one turn.
        XCTAssertEqual(pick(c, avoid: "latest", turn: 20_000), pick(c, avoid: "latest", turn: 20_000))
    }

    func test_should_skipARecentlyPlayedMemory_when_othersRemain() {
        let c = [memory("played", played: 9_000), memory("quiet", played: 1_000)]
        for turn in 0...5 {
            XCTAssertEqual(pick(c, avoid: nil, turn: Int64(turn), playedBefore: 5_000), "quiet")
        }
        // Alone, it still shows.
        XCTAssertEqual(pick([c[0]], avoid: nil, playedBefore: 5_000), "played")
    }

    func test_should_preferAnotherAlbum_when_theBubbleShowsTheLatest() {
        let c = [memory("latest", rating: 9, written: 9_000), memory("older", rating: 7, written: 1_000)]
        let latest = HomeMemories.pickLatest(c)?.albumId
        XCTAssertEqual(latest, "latest")
        XCTAssertEqual(pick(c, avoid: latest), "older")
    }

    func test_should_keepTheShownAlbum_when_itStillQualifies() {
        let c = [memory("latest", rating: 9, written: 9_000), memory("older", rating: 7, written: 1_000)]
        XCTAssertEqual(pick(c, avoid: "latest", prefer: "latest"), "latest")
    }

    func test_should_dropTheShownAlbum_when_itIsNoLongerAMemory() {
        XCTAssertEqual(pick([memory("rated", rating: 7)], avoid: nil, prefer: "gone"), "rated")
    }

    func test_should_reuseTheSameAlbum_when_onlyOneMemory() {
        XCTAssertEqual(pick([memory("only")], avoid: "only"), "only")
    }

    /// iOS difference: the card always shows a score, so unscored memories don't take a turn here.
    func test_should_dealOnlyScoredMemories_when_buildingTheCard() {
        let c = [memory("notes", rating: nil, notes: 4), memory("rated", rating: 7), memory("off", rating: 9, eligible: false)]
        for turn in 0...5 {
            XCTAssertEqual(HomeHistory.ratedAlbumSignal(c, turn: Int64(turn))?.album.id.rawId, "rated")
        }
        XCTAssertNil(HomeHistory.ratedAlbumSignal([memory("notes", rating: nil, notes: 4)]))
    }
}
