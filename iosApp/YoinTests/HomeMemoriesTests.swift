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

    func test_should_dealTheUnseenFirst_when_pullingPastTheDecksEnd() {
        let ms = (0..<9).map { memory("m\($0)", written: Int64($0 + 1), albumRating: 8) }
        let current: Set<String> = ["m0", "m1", "m2", "m3", "m4", "m5"]
        let next = MemoryDeck.next(.init(memories: ms), excluding: current)
        XCTAssertEqual(next.count, MemoryDeck.limit)
        XCTAssertEqual(Set(next.prefix(3).map(\.id)), ["m6", "m7", "m8"])
        XCTAssertTrue(next.dropFirst(3).allSatisfy { current.contains($0.id) })
        // A swipe on past the end turns the deck; a short, slow drag just springs back.
        XCTAssertTrue(MemoryDeck.turnsDeck(overshoot: 80, outwardVelocity: 0))
        XCTAssertTrue(MemoryDeck.turnsDeck(overshoot: 10, outwardVelocity: 700))
        XCTAssertFalse(MemoryDeck.turnsDeck(overshoot: 30, outwardVelocity: 100))
        XCTAssertFalse(MemoryDeck.turnsDeck(overshoot: 0, outwardVelocity: 900))
    }

    func test_should_dealSixWithTheFocusFirst_when_openingTheDeck() {
        let ms = (0..<10).map { memory("m\($0)", written: Int64($0 + 1), albumRating: 8) }
        var rng = SystemRandomNumberGenerator()
        for _ in 0..<20 {
            let deck = MemoryDeck.deck(.init(memories: ms), focus: "m3", using: &rng)
            XCTAssertEqual(deck.count, MemoryDeck.limit)
            XCTAssertEqual(MemoryDeck.limit, 6)
            XCTAssertEqual(deck.first?.id, "m3")
            XCTAssertEqual(Set(deck.map(\.id)).count, 6)
        }
        // No focus (or one gone): six from the pool all the same.
        XCTAssertEqual(MemoryDeck.deck(.init(memories: ms), focus: "gone").count, 6)
        XCTAssertEqual(MemoryDeck.deck(.init(memories: Array(ms.prefix(2))), focus: nil).count, 2)
    }

    func test_should_sayLastHeardWithTheYearOnlyWhenNotThisOne() {
        let en = Locale(identifier: "en_US")
        var c = DateComponents(); c.year = 2026; c.month = 11; c.day = 7; c.hour = 12
        let now = Calendar.current.date(from: c)!
        c.day = 2
        let ms = Int64(Calendar.current.date(from: c)!.timeIntervalSince1970 * 1000)
        XCTAssertEqual(MemoriesTopBar.lastHeard(ms, now: now, locale: en), "Nov 2")
        c.year = 2025
        let old = Int64(Calendar.current.date(from: c)!.timeIntervalSince1970 * 1000)
        XCTAssertEqual(MemoriesTopBar.lastHeard(old, now: now, locale: en), "Nov 2, 2025")
        XCTAssertNil(MemoriesTopBar.lastHeard(nil, now: now))
    }

    func test_should_sayWhatAndWho_when_inAMixedList() {
        XCTAssertEqual(KindLine.make("Album", "Kiri Ono"), "Album \u{2022} Kiri Ono")
        XCTAssertEqual(KindLine.make("Playlist", ""), "Playlist")
        XCTAssertEqual(KindLine.make("Playlist", "playlist"), "Playlist")
        XCTAssertEqual(KindLine.make("播放列表", "Playlist"), "播放列表")
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


/// Home and Memories rules from the 2026-11 review: a review alone makes a
/// memory, the albums memories need are the written ones, Memories survives a
/// push over Home, the retreat's bottom corners, the signed-out feed, the
/// Recently Played / Your Playlists shelves, and Apple Music's held seek.
@MainActor
final class HomeMemoriesReviewTests: XCTestCase {
    private func album(_ id: String, tracks: Int) -> Album {
        let ts = (0..<tracks).map { Track(id: MediaId(provider: "subsonic", rawId: "\(id)-\($0)"), number: $0 + 1,
                                          title: "T\($0)", durationSec: 200) }
        return Album(id: MediaId(provider: "subsonic", rawId: id), title: "Album \(id)", artist: "Artist",
                     year: 2020, palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: ts)
    }

    private func inputs(_ details: [Album], reviewed: Set<String> = [], ratings: [String: Double] = [:],
                        ratedAlbumIds: [String] = []) -> HomeHistory.Inputs {
        let byId = Dictionary(uniqueKeysWithValues: details.map { ($0.id.rawId, $0) })
        return HomeHistory.Inputs(now: 0, plays: [], notes: [], details: byId, known: [:],
                                  localRating: { ratings[$0] }, resolve: { id, _ in byId[id] },
                                  ratedAlbumIds: ratedAlbumIds, albumReviewed: { reviewed.contains($0) })
    }

    // MARK: #16 A review alone

    func test_should_beEligible_when_onlyAReviewIsWritten() {
        let m = HomeHistory.memories(inputs([album("r", tracks: 10)], reviewed: ["r"], ratedAlbumIds: ["r"]))
        XCTAssertEqual(m.count, 1)
        XCTAssertTrue(m[0].isMemoryEligible)
        XCTAssertTrue(m[0].hasAlbumReview)
        XCTAssertTrue(HomeHistory.hasMemory(m[0]))
        XCTAssertEqual(MemoryDeck.cards(MemoryDeck.Inputs(memories: m)).map(\.id), ["r"])
    }

    func test_should_keepTrackCountRule_when_reviewedAlbumIsShort() {
        let m = HomeHistory.memories(inputs([album("ep", tracks: 3)], reviewed: ["ep"], ratedAlbumIds: ["ep"]))
        XCTAssertFalse(m[0].isMemoryEligible)
        // Still something kept (Rediscover may bring it back as an album with no count rule).
        XCTAssertTrue(HomeHistory.hasMemory(m[0]))
    }

    // MARK: #14 What hydration loads

    func test_should_listWrittenAlbumsOnly_when_askingWhatMemoriesNeed() {
        let rated = album("rated", tracks: 4)
        var x = inputs([rated], ratings: ["rated-0": 8], ratedAlbumIds: ["synced"])
        x.plays = [PlayEvent(id: "p", scope: "s", provider: "subsonic", songId: "plain-0", albumId: "plain",
                             artistId: nil, title: "", album: "", artist: "", coverKey: nil, startedAt: 1, durationSec: 1)]
        // A synced album rating with nothing loaded, a rated song's album; a played-only album is not needed.
        XCTAssertEqual(HomeHistory.writtenAlbumIds(x), ["synced", "rated"])
    }

    // MARK: #8 Covered, not left

    func test_should_keepMemories_when_homeIsCoveredByAPush() {
        XCTAssertTrue(MemoriesPresentation.survivesHomeHidden(onHome: true, pushed: true))
        XCTAssertFalse(MemoriesPresentation.survivesHomeHidden(onHome: false, pushed: false))
        XCTAssertFalse(MemoriesPresentation.survivesHomeHidden(onHome: false, pushed: true))
        XCTAssertFalse(MemoriesPresentation.survivesHomeHidden(onHome: true, pushed: false))
    }

    func test_should_staySameOpen_when_notSnapped() {
        let p = MemoriesPresentation()
        p.open(focus: "a")
        let open = p.openCount
        // Covered by a push: nothing happens to q, so the deck finds the same open on the way back.
        XCTAssertTrue(p.isOpen)
        XCTAssertEqual(p.openCount, open)
        p.snapClosed()
        XCTAssertFalse(p.isOpen)
        XCTAssertFalse(p.mounted)
    }

    // MARK: #21 Bottom corners

    func test_should_roundCornersByQ_when_retreating() {
        let h: CGFloat = 800, commit = MemoriesPresentation.dismissCommit, full = MemoriesBackTokens.pageCornerRadius
        XCTAssertEqual(MemoriesPresentation.cornerRadius(fraction: 0, threshold: commit, height: h), 0)
        XCTAssertEqual(MemoriesPresentation.cornerRadius(fraction: commit / h, threshold: commit, height: h), full)
        XCTAssertEqual(MemoriesPresentation.cornerRadius(fraction: 0.9, threshold: commit, height: h), full)
        // Smoothstep: half way is half the radius.
        XCTAssertEqual(MemoriesPresentation.cornerRadius(fraction: commit / h / 2, threshold: commit, height: h),
                       full / 2, accuracy: 0.001)
        // The bar's shorter rule rounds sooner.
        XCTAssertGreaterThan(MemoriesPresentation.cornerRadius(fraction: 0.05, threshold: MemoriesBackTokens.barDismissCommit, height: h),
                             MemoriesPresentation.cornerRadius(fraction: 0.05, threshold: commit, height: h))
    }

    func test_should_useDragRule_when_dismissDragStarts() {
        let p = MemoriesPresentation()
        p.open()
        p.dismissDrag(10, rule: .bar)
        XCTAssertEqual(p.cornerThreshold, MemoriesBackTokens.barDismissCommit)
        p.dismissEnded(velocity: 0, rule: .bar)
        p.close()
        XCTAssertEqual(p.cornerThreshold, MemoriesPresentation.dismissCommit)
    }

    // MARK: #20 Signed out

    func test_should_showNoMockFeed_when_signedOutOutsideDemo() {
        XCTAssertNil(HomeView.feed(isReal: false, demo: false) { nil })
        XCTAssertNotNil(HomeView.feed(isReal: false, demo: true) { nil })
        XCTAssertNil(HomeView.feed(isReal: true, demo: true) { nil })
    }

    // MARK: #19 Recently Played, Your Playlists

    func test_should_fillRecentlyPlayedAndPlaylists_when_buildingTheFeed() {
        let recent = (0..<6).map { album("r\($0)", tracks: 5) }
        let lists = (0..<25).map {
            LibraryItem(id: MediaId(provider: "subsonic", rawId: "pl\($0)"), kind: .playlist, title: "P\($0)",
                        subtitle: "", palette: CoverPalette(a: .red, b: .green, c: .blue), count: 3)
        }
        let feed = LibraryStore.buildHome(recent: recent, frequent: [], newest: [], highest: [], random: [],
                                          recentTracks: [], playlists: lists)
        // Activities takes the first four; Recently Played shows the rest.
        XCTAssertEqual(feed.recentlyPlayed.map(\.id.rawId), ["r4", "r5"])
        XCTAssertEqual(feed.playlists.count, LibraryStore.homePlaylistLimit)
        XCTAssertTrue(HomeSection.recentlyPlayed.supportedOnIOS)
        XCTAssertTrue(HomeSection.yourPlaylists.supportedOnIOS)
        XCTAssertTrue(HomeSection.otherProfiles.supportedOnIOS)
    }

    // MARK: #15 Apple Music's held seek

    func test_should_startAtHeldSeek_when_seekCameWhilePreparing() {
        XCTAssertEqual(AppleMusicAudioOutput.startPosition(startSec: 0, pendingSeek: 83.2), 83.2)
        XCTAssertEqual(AppleMusicAudioOutput.startPosition(startSec: 12, pendingSeek: nil), 12)
    }
}
