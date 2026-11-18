import SwiftUI
import XCTest
@testable import Yoin

/// Memories' diary, router, award, title and layout rules, ported from
/// Android ui/memories (MemoriesDiaryStateTest, MemoriesGestureRouterTest,
/// MemoriesAwardLifecycleTest, MemoryTitleEditorTest, MemoriesMorphTest,
/// MemoriesLayoutTest with its golden table).
@MainActor
final class MemoriesShowcaseTests: XCTestCase {
    private let distance = MemoriesBackTokens.diaryMorphDistance
    private let band = MemoriesBackTokens.diaryPullBand
    private let flick = MemoriesPresentation.flickBack

    private func release(_ fraction: CGFloat, _ open: CGFloat, scrolled: Bool) -> CGFloat {
        MemoriesDiary.releaseTarget(fraction: fraction, openVelocity: open, distance: distance, band: band,
                                    flick: flick, fromScrolled: scrolled)
    }

    private func diary(at p: CGFloat) -> MemoriesDiary {
        let d = MemoriesDiary()
        d.snap(to: p)
        return d
    }

    // MARK: Diary (p)

    func test_should_stayInDiary_when_flingToTopStartedInText() {
        XCTAssertEqual(release((distance - 10) / distance, -3000, scrolled: true), 1)
        XCTAssertEqual(release((distance - 11.5) / distance, -3000, scrolled: true), 1)
        XCTAssertEqual(release((distance - 10) / distance, -3000, scrolled: false), 0)
        XCTAssertEqual(release((distance - 13) / distance, -3000, scrolled: true), 0)
        let d = diary(at: 1)
        d.startPull(banded: true, fromScrolled: true)
        d.pullBy(20)
        XCTAssertEqual(d.fraction * distance, distance - 10, accuracy: 1e-3)
        d.stop()
    }

    func test_should_closeWhenPastHalf_when_releasedWithoutFlick() {
        XCTAssertEqual(release(0.49, 0, scrolled: false), 0)
        XCTAssertEqual(release(0.51, 0, scrolled: false), 1)
        XCTAssertEqual(release(0.2, 351, scrolled: false), 1)
        XCTAssertEqual(release(0.8, -351, scrolled: false), 0)
        XCTAssertEqual(release(-0.1, 2000, scrolled: false), 0)
    }

    func test_should_halveFirstBand_when_pullStartsInText() {
        let d = diary(at: 1)
        d.startPull(banded: true, fromScrolled: true)
        d.pullBy(24)
        XCTAssertEqual(d.fraction * distance, distance - 12, accuracy: 1e-3)
        d.pullBy(24)
        XCTAssertEqual(d.fraction * distance, distance - 36, accuracy: 1e-3)
        d.pullBy(-48)
        XCTAssertEqual(d.fraction, 1, accuracy: 1e-6)
        let bar = diary(at: 1)
        bar.startPull(banded: false, fromScrolled: false)
        bar.pullBy(24)
        XCTAssertEqual(bar.fraction * distance, distance - 24, accuracy: 1e-3)
    }

    func test_should_handOverflowBackToScroll_when_pullReachesTheDiary() {
        let d = diary(at: 1)
        d.startPull(banded: true, fromScrolled: false)
        XCTAssertEqual(d.pullBy(-30), 0, accuracy: 1e-6)
        XCTAssertEqual(d.pullBy(10), 10, accuracy: 1e-6)
        XCTAssertEqual(d.pullBy(-30), -10, accuracy: 1e-6)
        XCTAssertEqual(d.fraction, 1, accuracy: 1e-6)
    }

    func test_should_rubberBandWithFloor_when_cardPulledDown() {
        let d = diary(at: 0)
        d.startPull(banded: false, fromScrolled: false, ceiling: 0)
        d.pullBy(100)
        XCTAssertEqual(d.fraction * distance, -30, accuracy: 1e-3)
        d.pullBy(10_000)
        XCTAssertEqual(d.fraction * distance, -90, accuracy: 1e-3)
        // Turning back up stops at the card: the pull-down never opens the diary.
        d.pullBy(-10_000)
        XCTAssertEqual(d.fraction, 0, accuracy: 1e-6)
    }

    func test_should_roundTrip_when_mappingPullAndMorph() {
        for travel in [-200, -12, 0, 100, distance - band - 1, distance - band, distance - 5, distance, distance + 40] {
            let morph = MemoriesDiary.pullToMorph(travel, distance: distance, band: band, banded: true)
            XCTAssertEqual(MemoriesDiary.morphToPull(morph, distance: distance, band: band, banded: true), travel,
                           accuracy: 1e-3, "travel \(travel)")
        }
    }

    func test_should_targetCard_when_releasedPastHalfTowardCard() {
        let d = diary(at: 1)
        d.startPull(banded: false, fromScrolled: false)
        d.pullBy(200)
        XCTAssertEqual(d.releasePull(velocity: 0), 0)
        XCTAssertEqual(d.target, 0)
        XCTAssertFalse(d.isDiaryLevel)
        d.stop()
    }

    // MARK: Router

    func test_should_routeEachZoneToOneController_when_dragLocks() {
        XCTAssertEqual(MemoriesRouting.route(zone: .bar, deltaY: -8, cardPresent: true), .dismiss(fromBar: true))
        XCTAssertEqual(MemoriesRouting.route(zone: .bar, deltaY: 8, cardPresent: true), .dismiss(fromBar: true))
        XCTAssertEqual(MemoriesRouting.route(zone: .card, deltaY: -8, cardPresent: true), .dismiss(fromBar: false))
        XCTAssertEqual(MemoriesRouting.route(zone: .card, deltaY: 8, cardPresent: true), .cardRubberBand)
        XCTAssertEqual(MemoriesRouting.route(zone: .card, deltaY: 8, cardPresent: false), .dismiss(fromBar: false))
        XCTAssertEqual(MemoriesRouting.route(zone: .bar, deltaY: 8, cardPresent: true, diaryLevel: true), .diaryHandle)
        XCTAssertEqual(MemoriesRouting.route(zone: .bar, deltaY: -8, cardPresent: true, diaryLevel: true), .dismiss(fromBar: true))
        XCTAssertEqual(MemoriesRouting.route(zone: .diary, deltaY: -8, cardPresent: true, diaryLevel: true), .diaryScroll)
        XCTAssertEqual(MemoriesRouting.route(zone: .diary, deltaY: -8, cardPresent: true, diaryLevel: true, diaryAtEnd: true),
                       .dismiss(fromBar: false))
        XCTAssertEqual(MemoriesRouting.route(zone: .diary, deltaY: 8, cardPresent: true, diaryLevel: true, diaryAtEnd: true),
                       .diaryScroll)
        XCTAssertEqual(MemoriesRouting.route(zone: .bar, deltaY: -8, cardPresent: true, titleEditing: true), .held)
    }

    func test_should_lockAxisAfterSlop_when_dragMoves() {
        XCTAssertNil(MemoriesRouting.axis(dx: 3, dy: 4, slop: 10))
        XCTAssertEqual(MemoriesRouting.axis(dx: 12, dy: 4, slop: 10), .horizontal)
        XCTAssertEqual(MemoriesRouting.axis(dx: 8, dy: 8, slop: 10), .vertical)
    }

    func test_should_shortenCommitBySlop_when_dismissLocksLate() {
        XCTAssertEqual(MemoriesDismissRule.body.afterSlop(0).commit, 112)
        XCTAssertEqual(MemoriesDismissRule.body.afterSlop(8).commit, 104)
        XCTAssertEqual(MemoriesDismissRule.bar.commit, 56)
        XCTAssertEqual(MemoriesPresentation.dismissTarget(dismissed: 60, velocity: 0, commit: 56, fling: 450), 1)
        XCTAssertEqual(MemoriesPresentation.dismissTarget(dismissed: 60, velocity: 0), 0)
        XCTAssertEqual(MemoriesPresentation.dismissTarget(dismissed: 10, velocity: 460, commit: 56, fling: 450), 1)
    }

    func test_should_feedOnlyItsController_when_routerDrives() {
        let reveal = MemoriesPresentation()
        reveal.containerHeight = 800
        let router = MemoriesGestureRouter(reveal: reveal, diary: MemoriesDiary())
        router.onThresholdCrossed = {}
        router.onCommitted = {}
        router.cardPresent = true
        // Card body pulled down: p's rubber band, q untouched.
        let r = router.begin(zone: .card, translationY: 10)
        XCTAssertEqual(r, .cardRubberBand)
        router.drag(r, translationY: 110)
        XCTAssertEqual(router.diary.fraction * distance, -30, accuracy: 1e-3)
        router.release(r, velocityY: 0)
        router.diary.stop()
    }

    func test_should_fireOncePerCrossing_when_valueMovesEitherWay() {
        var line = ThresholdCrossing(threshold: 112, initial: 0)
        XCTAssertFalse(line.update(60))
        XCTAssertTrue(line.update(112))
        XCTAssertFalse(line.update(130))
        XCTAssertTrue(line.update(100))
    }

    // MARK: Morph

    func test_should_holdTitleThenFly_when_pMoves() {
        XCTAssertEqual(MemoriesMorph.titleTravel(0.2, frozen: false), 0)
        XCTAssertEqual(MemoriesMorph.titleTravel(0.6, frozen: false), 0.5, accuracy: 1e-6)
        XCTAssertEqual(MemoriesMorph.titleTravel(0.9, frozen: true), 0)
        XCTAssertEqual(MemoriesMorph.coverProgress(0.275, frozen: true), 0.5, accuracy: 1e-6)
        XCTAssertEqual(MemoriesMorph.barCoverShown(0.5), 0)
        XCTAssertEqual(MemoriesMorph.barCoverShown(1), 1)
    }

    func test_should_landCoverInBarSlot_when_flightEnds() {
        let pose = MemoriesMorph.coverPose(fp: 1, coverCenter: CGPoint(x: 200, y: 250), barCenter: CGPoint(x: 84, y: 32),
                                           cover: 256, barCover: 40)
        XCTAssertEqual(pose.dx, -116, accuracy: 1e-6)
        XCTAssertEqual(pose.dy, -218, accuracy: 1e-6)
        XCTAssertEqual(pose.scale, 40.0 / 256, accuracy: 1e-6)
        XCTAssertEqual(pose.corner * pose.scale, MemoriesMorphTokens.coverCornerBar, accuracy: 1e-6)
        XCTAssertEqual(pose.alpha, 0, accuracy: 1e-6)
    }

    func test_should_handOverTitlesOnOnePath_when_pairTravels() {
        let card = TextAnchor(cx: 200, y: 400, font: 26), diary = TextAnchor(cx: 150, y: 100, font: 22)
        let mid = MemoriesMorph.textPair(card: card, diary: diary, t: 0.5)
        XCTAssertEqual(mid.card.dx + card.cx, mid.diary.dx + diary.cx, accuracy: 1e-6)
        XCTAssertEqual(mid.card.dy + card.y, mid.diary.dy + diary.y, accuracy: 1e-6)
        XCTAssertEqual(mid.card.alpha + mid.diary.alpha, 1, accuracy: 1e-6)
        let end = MemoriesMorph.textPair(card: card, diary: diary, t: 1)
        XCTAssertEqual(end.diary, MorphPose(dx: 0, dy: 0, scale: 1, alpha: 1))
    }

    func test_should_staggerBlocks_when_diaryRises() {
        XCTAssertEqual(MemoriesMorph.blockOffset(0, k: 2, lag: 100, frozen: false), 156)
        XCTAssertEqual(MemoriesMorph.blockOffset(0, k: 20, lag: 100, frozen: false), 100 + 6 * 28)
        XCTAssertEqual(MemoriesMorph.blockOffset(1, k: 2, lag: 100, frozen: false), 0)
        XCTAssertEqual(MemoriesMorph.blockOffset(0.5, k: 2, lag: 100, frozen: true), 0)
        XCTAssertEqual(MemoriesMorph.blockAlpha(0.26, k: 0, frozen: false), 0)
        XCTAssertEqual(MemoriesMorph.blockAlpha(0.92, k: 3, frozen: false), 1)
        XCTAssertEqual(MemoriesMorph.reducedCardAlpha(0.5), 0)
        XCTAssertEqual(MemoriesMorph.reducedDiaryAlpha(0.5), 0)
    }

    // MARK: Award lifecycle

    private final class FakeRun: MemoriesAwardRun {
        var climaxed = false
        var interrupted = 0, paused = 0, resumed = 0
        func interrupt() { interrupted += 1 }
        func pauseBeats() { paused += 1 }
        func resumeBeats() { resumed += 1 }
    }

    private final class FakeTarget: MemoriesAwardTarget {
        var hasAward = true
        var pending = 0
        var runs: [FakeRun] = []
        func showPending() { pending += 1 }
        func playFirst() -> MemoriesAwardRun {
            let r = FakeRun()
            runs.append(r)
            return r
        }
    }

    private final class FakeDem: MemoriesDiaryEmblemTarget {
        var hasAward = true
        var state = "rest", nods = 0
        func showWaiting() { state = "waiting" }
        func nod() { nods += 1 }
        func showRest() { state = "rest" }
    }

    private func inputs(_ key: String?, finger: Bool = false, near: Bool = true, reveal: Bool = true,
                        card: Bool = true, diary: Bool = false) -> MemoriesAwardInputs {
        MemoriesAwardInputs(cardKey: key, fingerDown: finger, nearCard: near, revealIn: reveal, cardState: card, diaryState: diary)
    }

    func test_should_awardOncePerOpen_when_cardFullyShown() {
        var now: Int64 = 1000
        let life = MemoriesAwardLifecycle(clock: { now })
        let a = FakeTarget()
        life.register("a", a)
        XCTAssertEqual(a.pending, 1)
        XCTAssertNil(life.startDelayMs(inputs("a", reveal: false)))
        XCTAssertNil(life.startDelayMs(inputs("a", finger: true)))
        XCTAssertNil(life.startDelayMs(inputs("a", card: false)))
        life.onFingerDown()
        now = 2000
        life.onFingerUp()
        now = 2050
        XCTAssertEqual(life.startDelayMs(inputs("a")), 70)
        XCTAssertTrue(life.start(inputs("a")))
        XCTAssertFalse(life.start(inputs("a")))
        XCTAssertEqual(a.runs.count, 1)
        XCTAssertEqual(MemoriesAwardLifecycle.startDelayMs(liftMs: 0, nowMs: 500), 0)
    }

    func test_should_neverAward_when_unrated() {
        let life = MemoriesAwardLifecycle(clock: { 0 })
        let u = FakeTarget()
        u.hasAward = false
        life.register("u", u)
        XCTAssertEqual(u.pending, 0)
        XCTAssertFalse(life.start(inputs("u")))
    }

    func test_should_unaward_when_leftBeforeClimax() {
        let life = MemoriesAwardLifecycle(clock: { 0 })
        let a = FakeTarget(), b = FakeTarget()
        life.register("a", a)
        life.register("b", b)
        life.start(inputs("a"))
        life.onDragStart()
        XCTAssertEqual(a.runs[0].paused, 1)
        life.onCardTargeted("a")
        XCTAssertEqual(a.runs[0].resumed, 1)
        life.onCardTargeted("b")
        XCTAssertEqual(a.runs[0].interrupted, 1)
        life.onPagerSettled("b")
        XCTAssertFalse(life.isAwarded("a"))
        XCTAssertEqual(a.pending, 2)
        // Past the climax it counts.
        life.start(inputs("b"))
        b.runs[0].climaxed = true
        life.onCardTargeted("a")
        life.onPagerSettled("a")
        XCTAssertTrue(life.isAwarded("b"))
    }

    func test_should_nodInDiary_andSkipNod_when_diaryOpenedOnDueCard() {
        let life = MemoriesAwardLifecycle(clock: { 0 })
        let a = FakeTarget(), b = FakeTarget()
        let da = FakeDem(), db = FakeDem()
        life.register("a", a)
        life.register("b", b)
        life.registerDiary("a", da)
        life.registerDiary("b", db)
        XCTAssertEqual(da.state, "waiting")
        // The Diary button on a due card: no nod, the 48 waits uncut.
        life.onDiaryOpening("a")
        XCTAssertTrue(life.isNodded("a"))
        XCTAssertFalse(life.nod(inputs("a", card: false, diary: true)))
        // Met in the diary: a nod, never the award.
        XCTAssertFalse(life.start(inputs("b", card: false, diary: true)))
        XCTAssertTrue(life.nod(inputs("b", card: false, diary: true)))
        XCTAssertEqual(db.nods, 1)
        // Back on the card: the award cuts both.
        XCTAssertTrue(life.start(inputs("b")))
        XCTAssertEqual(db.state, "rest")
    }

    // MARK: Titles

    private func card(_ kind: MemoryCard.TitleKind, title: String = "Night buses", yoin: String? = "Yoin's") -> MemoryCard {
        var c = MemoryDeck.card(MemoryDeck.mockMemories()[0], MemoryDeck.Inputs(memories: []))
        c.titleKind = kind
        c.title = kind == .album ? c.album.title : title
        c.yoinTitle = yoin
        return c
    }

    func test_should_writeNothing_when_savedUnchanged() {
        XCTAssertEqual(MemoryTitles.outcome(draft: "  Night buses ", seed: "Night buses", userTitleInUse: true), .unchanged)
        XCTAssertEqual(MemoryTitles.outcome(draft: "", seed: "", userTitleInUse: false), .unchanged)
    }

    func test_should_restore_when_userTitleSavedEmpty() {
        XCTAssertEqual(MemoryTitles.outcome(draft: "  ", seed: "Mine", userTitleInUse: true), .restore)
        XCTAssertEqual(MemoryTitles.outcome(draft: "", seed: "AI", userTitleInUse: false), .unchanged)
        XCTAssertEqual(MemoryTitles.outcome(draft: " New one ", seed: "AI", userTitleInUse: false), .save("New one"))
    }

    func test_should_keepOneCappedLine_when_typedOrPasted() {
        XCTAssertEqual(MemoryTitles.sanitize("a\nb\rc"), "a b c")
        XCTAssertEqual(MemoryTitles.sanitize(String(repeating: "x", count: 100)).count, MemoryTitles.maxLength)
    }

    func test_should_seedAndHint_when_fieldOpens() {
        let album = card(.album)
        XCTAssertEqual(MemoryTitles.seed(album), "")
        XCTAssertEqual(MemoryTitles.placeholder(album), "Yoin's")
        XCTAssertEqual(MemoryTitles.placeholder(card(.album, yoin: nil)), album.album.title)
        XCTAssertEqual(MemoryTitles.seed(card(.user)), "Night buses")
        XCTAssertTrue(MemoryTitles.canRestore(card(.user)))
        XCTAssertFalse(MemoryTitles.canRestore(card(.ai)))
        XCTAssertFalse(MemoryTitles.canRestore(card(.user, yoin: nil)))
        let restored = MemoryTitles.applied(.restore, to: card(.user))
        XCTAssertEqual(restored.title, "Yoin's")
        XCTAssertEqual(restored.titleKind, .ai)
        XCTAssertEqual(MemoryTitles.applied(.restore, to: card(.user, yoin: nil)).titleKind, .album)
    }

    func test_should_openOneSurface_when_titleTapped() {
        let editor = MemoryTitleEditor()
        let c = card(.ai)
        editor.begin(.diary, c)
        XCTAssertTrue(editor.isEditing(.diary, c))
        XCTAssertFalse(editor.isEditing(.card, c))
        XCTAssertEqual(editor.draft, "Night buses")
        editor.end()
        XCTAssertFalse(editor.isEditing)
    }

    // MARK: Deck data

    func test_should_listRatedOrNotedTracksInOrder_when_buildingTheLiner() {
        let album = MockLibrary.allAlbums[0]
        let t = album.tracks
        func note(_ i: Int, _ at: Int64?, _ created: Int64) -> SongNote {
            SongNote(id: "\(i)-\(created)", provider: "mock", trackId: t[i].id.rawId, content: "n", createdAt: created,
                     updatedAt: created, title: "", artist: "", positionMs: at)
        }
        let rows = MemoryDeck.diaryTracks(album, notes: [note(2, nil, 1), note(2, 5000, 2), note(2, 1000, 3)]) {
            $0 == t[0].id.rawId ? 8 : nil
        }
        XCTAssertEqual(rows.map(\.index), [0, 2])
        XCTAssertEqual(rows[1].notes.map(\.positionMs), [1000, 5000, nil])
    }

    func test_should_countDaysSinceFirstPlay_when_footerShows() {
        let cal = Calendar(identifier: .gregorian)
        let day: Int64 = 86_400_000
        XCTAssertNil(MemoryDeck.footer(plays: 0, firstPlayedAt: 1, now: day, calendar: cal))
        XCTAssertNil(MemoryDeck.footer(plays: 3, firstPlayedAt: nil, now: day, calendar: cal))
        XCTAssertEqual(MemoryDeck.footer(plays: 3, firstPlayedAt: day * 10 + 1000, now: day * 13 + 5000, calendar: cal)?.days, 3)
    }

    // MARK: Layout (golden table from the prototype)

    func test_should_matchPrototypeTable_when_budgetingContainers() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "memories-layout-table", withExtension: "json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let rows = (json["table"] as? [[String: Any]] ?? []) + (json["extra"] as? [[String: Any]] ?? [])
        XCTAssertGreaterThan(rows.count, 10)
        func num(_ r: [String: Any], _ k: String) -> CGFloat? { (r[k] as? NSNumber).map { CGFloat($0.doubleValue) } }
        for r in rows {
            let w = num(r, "w")!, h = num(r, "h")!
            // Phone landscape deviates from the prototype on purpose (a spread with a lower floor).
            if h < MemoriesLayoutTokens.landscapeBelow && w >= MemoriesLayoutTokens.mediumFrom {
                let l = MemoriesLayouts.layout(width: w, height: h)
                XCTAssertTrue(l.isSpread && l.landscape && l.minCover == MemoriesLayoutTokens.landscapeMinCover)
                continue
            }
            let l = MemoriesLayouts.layout(width: w, height: h)
            let tag = "\(w)×\(h)"
            XCTAssertEqual(l.tier.rawValue, r["tier"] as? String, tag)
            XCTAssertEqual(l.short, r["short"] as? Bool, tag)
            XCTAssertEqual(l.cover, num(r, "cover"), tag)
            XCTAssertEqual(l.seal, num(r, "seal"), tag)
            XCTAssertEqual(l.pillStart, num(r, "pl"), tag)
            XCTAssertEqual(l.dotsEnd, num(r, "dr"), tag)
            if let lp = num(r, "lp") { XCTAssertEqual(l.leftPage, lp, tag) }
            if let m = num(r, "measure") { XCTAssertEqual(l.measure, m, tag) }
            if let d = num(r, "dcol") { XCTAssertEqual(l.diaryColumn, d, tag) }
            if let a = num(r, "air1") { XCTAssertEqual(l.air1, a, tag) }
            if let c = num(r, "minCov") { XCTAssertEqual(l.minCover, c, tag) }
        }
    }
}
