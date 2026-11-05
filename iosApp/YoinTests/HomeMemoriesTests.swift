import SwiftUI
import XCTest
@testable import Yoin

/// The Memories bubble's pure rules (pill, news, turns, geometry), the
/// Memories reveal rules and the deck.
@MainActor
final class HomeMemoriesTests: XCTestCase {
    private func memory(_ id: String, written: Int64? = nil, played: Int64? = nil, eligible: Bool = true,
                        albumRating: Double? = nil, average: Double? = nil, notes: Int = 0,
                        tracks: Int = 6) -> AlbumMemory {
        let ts = (0..<tracks).map { Track(id: MediaId(provider: "subsonic", rawId: "\(id)-\($0)"), number: $0 + 1,
                                          title: "T\($0)", durationSec: 200) }
        let album = Album(id: MediaId(provider: "subsonic", rawId: id), title: "Album \(id)", artist: "Artist",
                          year: 2020, palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: ts)
        return AlbumMemory(album: album, totalTracks: tracks, ratedTrackCount: average == nil ? 0 : 3, coverage: 0.5,
                           averageSongRating: average, albumRating: albumRating, noteCount: notes,
                           lastWrittenAt: written, playCount: 1, firstPlayedAt: played, lastPlayedAt: played,
                           isMemoryEligible: eligible)
    }

    // MARK: Pill

    func test_should_pickNewestWrite_when_choosingTheLatest() {
        let ms = [memory("a", written: 100, played: 900), memory("b", written: 300), memory("c", played: 5000)]
        // No write at all: the newest play is the key.
        XCTAssertEqual(HomeMemories.pickLatest(ms)?.albumId, "c")
        let written = [memory("a", written: 100, played: 900), memory("b", written: 300)]
        XCTAssertEqual(HomeMemories.pickLatest(written)?.albumId, "b")
        // A tie keeps the first (strength-ranked) one.
        XCTAssertEqual(HomeMemories.pickLatest([memory("x", written: 5), memory("y", written: 5)])?.albumId, "x")
    }

    func test_should_listWrittenFirstWithoutLatest_when_recalling() {
        let ms = [memory("a", played: 999), memory("b", written: 10), memory("c", written: 20), memory("b", written: 1)]
        let latest = ms[2]
        XCTAssertEqual(HomeMemories.pickRecall(ms, latest: latest).map(\.albumId), ["b", "a"])
    }

    func test_should_readEligibleOnly_when_buildingThePill() {
        let ms = [memory("x", written: 500, eligible: false), memory("y", written: 100, albumRating: 8.5)]
        let pill = HomeMemories.pill(ms, noteCount: 3, scope: "s") { $0.albumId == "y" ? "Title" : nil }
        XCTAssertEqual(pill.latest?.albumId, "y")
        XCTAssertEqual(pill.latest?.scoreText, "8.5")
        XCTAssertEqual(pill.latest?.memoryTitle, "Title")
        XCTAssertEqual(pill.newsKey, "100#3")
        XCTAssertTrue(pill.hasSomethingToSay)
        XCTAssertFalse(HomeMemories.pill([], noteCount: 0, scope: "s").hasSomethingToSay)
        XCTAssertTrue(HomeMemories.pill([], noteCount: 2, scope: "s").hasSomethingToSay)
    }

    func test_should_beNewsOnlyWhenEitherPartGrows_when_comparingKeys() {
        let pill = HomeMemories.pill([memory("y", written: 100)], noteCount: 3, scope: "s")
        XCTAssertTrue(HomeMemories.isNews(pill, lastSeen: nil))
        XCTAssertTrue(HomeMemories.isNews(pill, lastSeen: "garbage"))
        XCTAssertTrue(HomeMemories.isNews(pill, lastSeen: "99#3"))
        XCTAssertTrue(HomeMemories.isNews(pill, lastSeen: "100#2"))
        XCTAssertFalse(HomeMemories.isNews(pill, lastSeen: "100#3"))
        // A deletion moved it back: not news.
        XCTAssertFalse(HomeMemories.isNews(pill, lastSeen: "200#5"))
        XCTAssertFalse(HomeMemories.isNews(nil, lastSeen: nil))
    }

    func test_should_rotateRecall_when_idleReminderSpeaks() {
        let pill = HomeMemories.pill([memory("a", written: 3), memory("b", written: 2), memory("c", written: 1)],
                                     noteCount: 0, scope: "s")
        XCTAssertEqual(HomeMemories.subject(pill, reason: .news, turn: 7)?.albumId, "a")
        XCTAssertEqual(HomeMemories.subject(pill, reason: .idle, turn: 0)?.albumId, "b")
        XCTAssertEqual(HomeMemories.subject(pill, reason: .idle, turn: 1)?.albumId, "c")
        XCTAssertEqual(HomeMemories.subject(pill, reason: .idle, turn: 2)?.albumId, "b")
    }

    // MARK: Turns

    func test_should_followAndroidRules_when_cued() {
        let quiet = BubbleTurn()
        XCTAssertEqual(quiet.after(.news), BubbleTurn(reason: .news, spokeThisForeground: true))
        XCTAssertEqual(quiet.after(.idleElapsed).reason, .idle)
        // Never idle chatter over a bubble already speaking.
        XCTAssertEqual(BubbleTurn(reason: .news, spokeThisForeground: true).after(.idleElapsed).reason, .news)
        for cue in [BubbleCue.touch, .dwellOver, .outOfReach] {
            let t = BubbleTurn(reason: .idle, spokeThisForeground: true).after(cue)
            XCTAssertNil(t.reason)
            XCTAssertTrue(t.spokeThisForeground)
        }
        XCTAssertEqual(BubbleTurn(reason: .idle, spokeThisForeground: true).after(.backgrounded), BubbleTurn())
    }

    // MARK: Geometry

    func test_should_placeArrowUnderCutoutWhenRoom_else_fallBack() {
        XCTAssertEqual(BubbleLayout.arrowCenterX(cutoutCenterX: 201, pageCentre: 201, titleLeft: 16, freeEnd: 340).x, 201)
        XCTAssertTrue(BubbleLayout.arrowCenterX(cutoutCenterX: 201, pageCentre: 201, titleLeft: 16, freeEnd: 340).underCutout)
        // No room past the arrow before Settings: title left + 150.
        let narrow = BubbleLayout.arrowCenterX(cutoutCenterX: 201, pageCentre: 201, titleLeft: 16, freeEnd: 250)
        XCTAssertEqual(narrow.x, 166)
        XCTAssertFalse(narrow.underCutout)
    }

    func test_should_growFromMarkToBody_when_unrolling() {
        let g = BubbleLayout.geometry(titleFrame: CGRect(x: 16, y: 74, width: 120, height: 60), arrowX: 201, arrowCenterY: 60)
        XCTAssertEqual(g.body, CGRect(x: 16, y: 74, width: 265, height: 60))
        XCTAssertEqual(g.tailTop, 34)
        XCTAssertEqual(g.markEnd, CGPoint(x: 261, y: 124))
        let start = g.body(at: 0)
        // About the mark's own box (Android bodyAt: its right end rides the mark at the corner inset).
        XCTAssertEqual(start.width, BubbleMetrics.markSize / 2 + BubbleMetrics.markCornerInsetX, accuracy: 0.001)
        XCTAssertEqual(g.body(at: 1), g.body)
        // The tail fits under the round corner at rest (Android: body ends 80 past the chevron).
        XCTAssertTrue(BubbleOutline(body: g.body, radius: BubbleMetrics.corner, tailX: 201, tailTop: g.tailTop).hasTail)
        XCTAssertFalse(BubbleOutline(body: start, radius: BubbleMetrics.corner, tailX: 201, tailTop: g.tailTop).hasTail)
    }

    func test_should_landEveryArmOnOne_when_markBlooms() {
        for i in 0..<3 { XCTAssertEqual(BubbleLayout.armProgress(1, arm: i), 1, accuracy: 1e-9) }
        XCTAssertEqual(BubbleLayout.armProgress(0.1, arm: 1), 0)
        XCTAssertEqual(BubbleLayout.flightAlpha(0), 1)
        XCTAssertEqual(BubbleLayout.flightAlpha(1), 0)
        XCTAssertEqual(BubbleLayout.arrowHomecoming(0.5), 0)
    }

    // MARK: Memories reveal (RevealState rules)

    func test_should_clampOpenAndBandPastClosed_when_dragged() {
        XCTAssertEqual(MemoriesPresentation.rubberBanded(-0.4), 0)
        XCTAssertEqual(MemoriesPresentation.rubberBanded(0.3), 0.3)
        let over = MemoriesPresentation.rubberBanded(2)
        XCTAssertGreaterThan(over, 1)
        XCTAssertLessThan(over, 1.3)
    }

    func test_should_settleByPositionOrVelocity_when_released() {
        XCTAssertEqual(MemoriesPresentation.settleTarget(0.4, velocity: 0), 0)
        XCTAssertEqual(MemoriesPresentation.settleTarget(0.6, velocity: 0), 1)
        XCTAssertEqual(MemoriesPresentation.settleTarget(0.9, velocity: -2), 0)
        XCTAssertEqual(MemoriesPresentation.settleTarget(0.1, velocity: 2), 1)
    }

    func test_should_commitPast112OrFling_unlessFlickedBack_when_retreating() {
        XCTAssertEqual(MemoriesPresentation.dismissTarget(dismissed: 120, velocity: 0), 1)
        XCTAssertEqual(MemoriesPresentation.dismissTarget(dismissed: 40, velocity: 700), 1)
        XCTAssertEqual(MemoriesPresentation.dismissTarget(dismissed: 40, velocity: 100), 0)
        XCTAssertEqual(MemoriesPresentation.dismissTarget(dismissed: 300, velocity: -400), 0)
    }

    func test_should_springOneControllerAllWays_when_openedAndClosed() {
        let p = MemoriesPresentation()
        p.containerHeight = 800
        XCTAssertFalse(p.mounted)
        p.pull(400)
        XCTAssertEqual(p.fraction, 0.5, accuracy: 1e-6)
        XCTAssertTrue(p.mounted)
        XCTAssertFalse(p.pullEnded(velocity: 50))
        p.open(focus: "al1")
        XCTAssertTrue(p.isOpen)
        XCTAssertEqual(p.focusAlbumId, "al1")
        p.snapClosed()
        XCTAssertFalse(p.mounted)
        XCTAssertEqual(p.fraction, 1)
    }

    // MARK: Deck

    func test_should_orderWrittenFirstAndStopOnFocus_when_buildingTheDeck() {
        let ms = [memory("p", played: 9999), memory("w1", written: 10, albumRating: 9),
                  memory("w2", written: 50, average: 7.5), memory("no", written: 99, eligible: false)]
        let note = SongNote(id: "n", provider: "subsonic", trackId: "w2-1", content: "  the bridge  ", createdAt: 1,
                            updatedAt: 2, title: "T1", artist: "A")
        let cards = MemoryDeck.cards(.init(memories: ms, notes: ["w2": [note]],
                                           review: { $0 == "w1" ? ("Loud.", 0) : nil },
                                           title: { $0 == "w1" ? ("Mine", true) : nil },
                                           rating: { $0 == "w2-0" ? 8 : nil }))
        XCTAssertEqual(cards.map(\.id), ["w2", "w1", "p"])
        XCTAssertEqual(cards[0].excerpt, .init(kind: .note, text: "the bridge", detail: "T1"))
        XCTAssertEqual(cards[0].emblemKind, .average)
        XCTAssertEqual(cards[0].rated.first, true)
        XCTAssertEqual(cards[1].title, "Mine")
        XCTAssertEqual(cards[1].titleKind, .user)
        XCTAssertEqual(cards[1].excerpt?.kind, .review)
        XCTAssertEqual(cards[2].titleKind, .album)
        XCTAssertEqual(MemoryDeck.startIndex(cards, focus: "w1"), 1)
        XCTAssertEqual(MemoryDeck.startIndex(cards, focus: "gone"), 0)
    }

    func test_should_turnOnFlickOrPastHalf_when_pagerReleased() {
        XCTAssertEqual(MemoryDeck.settlePage(position: 1.4, velocity: 0, count: 5), 1)
        XCTAssertEqual(MemoryDeck.settlePage(position: 1.6, velocity: 0, count: 5), 2)
        XCTAssertEqual(MemoryDeck.settlePage(position: 1.1, velocity: 1, count: 5), 2)
        XCTAssertEqual(MemoryDeck.settlePage(position: 2.0, velocity: -1, count: 5), 1)
        XCTAssertEqual(MemoryDeck.settlePage(position: 4.2, velocity: 3, count: 5), 4)
        XCTAssertEqual(MemoryDeck.settlePage(position: -0.2, velocity: -3, count: 5), 0)
    }

    func test_should_includeRatingAndAlbumWrites_when_memoryTracksLastWritten() {
        let album = Album(id: MediaId(provider: "subsonic", rawId: "a"), title: "A", artist: "X", year: 0,
                          palette: CoverPalette(a: .red, b: .green, c: .blue),
                          tracks: [Track(id: MediaId(provider: "subsonic", rawId: "s1"), number: 1, title: "S", durationSec: 1)])
        let inputs = HomeHistory.Inputs(now: 0, plays: [], notes: [], details: ["a": album], known: [:],
                                        localRating: { $0 == "s1" ? 8 : nil }, resolve: { _, _ in nil },
                                        ratingWrittenAt: { $0 == "s1" ? 777 : nil }, albumWrittenAt: { _ in 500 })
        XCTAssertEqual(HomeHistory.memories(inputs).first?.lastWrittenAt, 777)
    }
}
