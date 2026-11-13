import XCTest
@testable import Yoin

/// Lyric Cards: the question, the answer's check, which songs get a card, and the budget
/// (Data/Lyrics/LyricHighlight.swift, ported from Android data/highlight/ #180 #189).
final class LyricHighlightTests: XCTestCase {
    private func prepared(_ lines: [String]) -> LyricHighlightPrompt.Prepared {
        LyricHighlightPrompt.prepare(lines.enumerated().map { ($0.element, Int64($0.offset) * 1000) })!
    }

    private let song = ["I walk the line", "Under neon light", "Every night the city sighs", "And I fall into its eyes",
                        "Oh oh oh", "I walk the line", "The end"]

    func test_should_numberSungLinesOnce_when_prepared() {
        let p = prepared(song + ["(Instrumental)", "[Chorus]", "", "123"])
        XCTAssertEqual(p.lines.map(\.text), ["I walk the line", "Under neon light", "Every night the city sighs",
                                             "And I fall into its eyes", "Oh oh oh", "The end"])
        // The repeat is left out, but the song's own count goes on past it.
        XCTAssertEqual(p.lines.last?.source, 6)
        XCTAssertTrue(p.fingerprint.hasPrefix(LyricHighlightPrompt.promptVersion))
    }

    func test_should_skipSongs_when_tooLittleIsSung() {
        XCTAssertNil(LyricHighlightPrompt.prepare([("La", 0), ("La", 1), ("(Solo)", 2)]))
    }

    func test_should_readJsonAndNearMisses_when_parsing() {
        let p = prepared(song)
        XCTAssertEqual(LyricHighlightPrompt.parse("{\"start\": 3, \"end\": 4}", p)?.text,
                       "Every night the city sighs\nAnd I fall into its eyes")
        XCTAssertEqual(LyricHighlightPrompt.parse("```json\n{\"start\":3,\"end\":4}\n```", p)?.first.number, 3)
        XCTAssertEqual(LyricHighlightPrompt.parse("2-3", p)?.last.number, 3)
        XCTAssertEqual(LyricHighlightPrompt.parse("3", p)?.startMs, 2000)
        XCTAssertNil(LyricHighlightPrompt.parse("{\"start\": 40}", p))
        XCTAssertNil(LyricHighlightPrompt.parse("{\"start\": 4, \"end\": 2}", p))
        XCTAssertNil(LyricHighlightPrompt.parse("I like line three", p))
    }

    func test_should_neverPickOneLine_when_theSongHasANeighbour() {
        let p = prepared(song)
        // A one-line answer grows to two (the next line follows in the song).
        XCTAssertEqual(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 1}", p)?.text, "I walk the line\nUnder neon light")
        // The last line has no neighbour in the song (a repeat stood before it): it stays alone.
        XCTAssertEqual(LyricHighlightPrompt.parse("{\"start\": 6}", p)?.text, "The end")
        // The second-to-last line takes the one before it instead.
        XCTAssertEqual(LyricHighlightPrompt.parse("{\"start\": 5}", p)?.text, "And I fall into its eyes\nOh oh oh")
    }

    func test_should_notJoinAcrossALeftOutRepeat_when_numbersAreAdjacent() {
        // Line 5 ("Oh oh oh") is source 4, line 6 ("The end") is source 6: a repeat stood between them.
        let p = prepared(song)
        XCTAssertEqual(LyricHighlightPrompt.parse("{\"start\": 5, \"end\": 6}", p)?.text, "And I fall into its eyes\nOh oh oh")
    }

    func test_should_dropTrailingLines_when_thePassageIsTooLong() {
        let long = String(repeating: "word ", count: 20)
        let p = prepared([long + "a", long + "b", long + "c", "d", "e"])
        XCTAssertEqual(LyricHighlightPrompt.parse("{\"start\": 1, \"end\": 3}", p)?.text.components(separatedBy: "\n").count, 1)
    }

    func test_should_mixHeavyRediscoverAndFresh_when_queueing() {
        let now: Int64 = 400 * LyricHighlightSelector.dayMs
        let signals: [LyricHighlightSelector.SongSignal] = [
            .init(songId: "hot", playCount: 9, lastPlayedAt: now, heat: 4, rating: nil, noteCount: 0),
            .init(songId: "old", playCount: 5, lastPlayedAt: now - 100 * LyricHighlightSelector.dayMs, heat: 0, rating: 8, noteCount: 0),
            .init(songId: "once", playCount: 1, lastPlayedAt: now - 5 * LyricHighlightSelector.dayMs, heat: 0.8, rating: nil, noteCount: 0),
        ]
        let pools = LyricHighlightSelector.pools(signals, fresh: [.init(songId: "new", albumRating: 9), .init(songId: "hot", albumRating: 9)], now: now)
        XCTAssertEqual(pools[.heavy]?.map(\.songId), ["hot"])
        XCTAssertEqual(pools[.rediscover]?.map(\.songId), ["old"])
        XCTAssertEqual(pools[.fresh]?.map(\.songId), ["new"], "a played track is never fresh")
        let q = LyricHighlightSelector.processingQueue(pools, skip: [], limit: 10)
        XCTAssertEqual(q.map(\.songId), ["hot", "old", "new"])
        XCTAssertEqual(LyricHighlightSelector.processingQueue(pools, skip: ["hot"], limit: 10).map(\.songId), ["old", "new"])
        XCTAssertNil(LyricHighlightSelector.bucket(of: signals[2], now: now))
        XCTAssertEqual(LyricHighlightSelector.bucket(of: nil, now: now), .fresh)
    }

    func test_should_countOnlyHeardPlays_when_heatIsSummed() {
        let now: Int64 = 100 * LyricHighlightSelector.dayMs
        XCTAssertEqual(LyricHighlightSelector.heat(playedAt: now, completed: 0.2, now: now), 0)
        XCTAssertEqual(LyricHighlightSelector.heat(playedAt: now, completed: 1, now: now), 1, accuracy: 1e-9)
        XCTAssertEqual(LyricHighlightSelector.heat(playedAt: now - LyricHighlightSelector.heatHalfLifeMs, completed: 1, now: now),
                       0.5, accuracy: 1e-9)
    }

    func test_should_preferTheLongestUnshown_when_choosingTheNextCard() {
        var rng = SystemRandomNumberGenerator()
        let now: Int64 = 100 * LyricHighlightSelector.dayMs
        let shown: [LyricHighlightSelector.Shown] = [
            .init(songId: "a", lastShownAt: now - LyricHighlightSelector.dayMs),
            .init(songId: "b", lastShownAt: nil),
            .init(songId: "c", lastShownAt: now - 30 * LyricHighlightSelector.dayMs),
        ]
        for _ in 0..<20 {
            let pick = LyricHighlightSelector.nextToShow(shown, bucketOf: { _ in .heavy }, now: now, exclude: "b", using: &rng)
            XCTAssertEqual(pick?.songId, "c", "a was shown this week; b is playing")
        }
    }

    func test_should_capTheDayAndBackOff_when_budgetIsSpent() {
        var b = LyricHighlightBudget()
        for _ in 0..<LyricHighlightBudget.dailyLimit { b = b.afterSuccess(today: 7) }
        XCTAssertFalse(b.allows(now: 0, today: 7))
        XCTAssertTrue(b.allows(now: 0, today: 8), "a new day starts over")
        var busy = LyricHighlightBudget().afterBusy(now: 1_000, today: 7)
        XCTAssertFalse(busy.allows(now: 1_000 + 29_000, today: 7))
        XCTAssertTrue(busy.allows(now: 1_000 + 30_000, today: 7))
        busy = busy.afterBusy(now: 100_000, today: 7)
        XCTAssertEqual(busy.pausedUntil, 100_000 + 120_000)
        XCTAssertEqual(LyricHighlightBudget.dayOf(86_400_000 - 1, utcOffsetSec: 3600), 1)
    }

    @MainActor
    func test_should_keepTheUserPick_when_theModelPicksLater() {
        let store = LyricHighlightStore(fileURL: nil, defaults: UserDefaults(suiteName: "lyric-highlight-tests")!)
        func row(_ text: String?, model: String?) -> LyricHighlightRow {
            LyricHighlightRow(provider: "subsonic", scope: "s", songId: "1", title: "T", artist: "A", album: "Al", albumId: "al",
                              coverKey: nil, durationMs: 1, text: text, startMs: nil, fingerprint: nil, model: model, attemptedAt: 1)
        }
        store.save(row("mine", model: LyricHighlightStore.userPick))
        store.save(row("model's", model: "apple-on-device"))
        XCTAssertEqual(store.row(provider: "subsonic", scope: "s", songId: "1")?.text, "mine")
        let other = LyricHighlightStore(fileURL: nil, defaults: UserDefaults(suiteName: "lyric-highlight-tests")!)
        other.save(row("picked", model: "m"))
        other.save(row(nil, model: nil))
        XCTAssertEqual(other.row(provider: "subsonic", scope: "s", songId: "1")?.text, "picked", "a failed try never erases a card")
        XCTAssertEqual(other.cards(provider: "subsonic", scope: "s").count, 1)
        XCTAssertTrue(other.cards(provider: "subsonic", scope: "other").isEmpty)
    }

    func test_should_turnPlaysIntoSignals_when_historyIsRead() {
        let now: Int64 = 50 * LyricHighlightSelector.dayMs
        func play(_ id: String, _ at: Int64, listened: Double, completed: Bool) -> PlayEvent {
            PlayEvent(id: UUID().uuidString, scope: "s", provider: "subsonic", songId: id, albumId: nil, artistId: nil,
                      title: "", album: "", artist: "", coverKey: nil, startedAt: at, durationSec: 200, listenedSec: listened,
                      completed: completed)
        }
        let signals = LyricHighlightProcessor.signals(
            plays: [play("a", now, listened: 200, completed: true), play("a", now, listened: 190, completed: true),
                    play("b", now, listened: 10, completed: false)],
            noteCount: ["a": 2], rating: { $0 == "a" ? 9 : nil }, now: now)
        let a = signals.first { $0.songId == "a" }!
        XCTAssertEqual(a.playCount, 2)
        XCTAssertEqual(a.heat, 2, accuracy: 1e-6)
        XCTAssertEqual(a.noteCount, 2)
        XCTAssertEqual(signals.first { $0.songId == "b" }?.heat, 0)
    }
}
