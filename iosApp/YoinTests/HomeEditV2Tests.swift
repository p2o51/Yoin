import XCTest
@testable import Yoin

/// Home edit v2 on iOS (parity p4 B1; design.md §主页 › 就地编辑): the in-place drag through the edit
/// controller (one write and one Undo step per drop, the edge scroll, the interruptions: Done keeps the drop,
/// a blank tap, Back, a resize or the background roll it back), the press hit test the gesture machine reads,
/// the machine driven by touch records on their own clock, the pinch into the compact list, and VoiceOver's words.
@MainActor
final class HomeEditV2Tests: XCTestCase {
    private func tempStore() -> HomeLayoutStore {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("home-v2-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return HomeLayoutStore(fileURL: url)
    }

    /// A feed of fixed blocks: 300 pt tall, 18 apart, the first at y = 100 minus the scroll; the viewport is 0…900.
    private final class FeedHost: HomeDragHost {
        var order: [HomeSection]
        var scrolled: CGFloat = 0
        var scrollMax: CGFloat = 100_000
        var isList: Bool { false }
        init(_ order: [HomeSection]) { self.order = order }

        func bands(for order: [HomeSection]) -> [(section: HomeSection, top: CGFloat, bottom: CGFloat)] {
            var y = 100 - scrolled
            return order.map { s in
                defer { y += 318 }
                return (s, y, y + 300)
            }
        }

        var contentLeft: CGFloat { 16 }
        var contentWidth: CGFloat { 370 }
        var scrollArea: (top: CGFloat, bottom: CGFloat) { (0, 900) }
        var viewportTop: CGFloat { scrolled }

        func scrollBy(_ dy: CGFloat) -> CGFloat {
            let consumed = min(max(dy, -scrolled), scrollMax - scrolled)
            scrolled += consumed
            return consumed
        }
    }

    /// The drag holds its host weakly: the tests keep theirs.
    private var hosts: [FeedHost] = []

    private func editing(_ store: HomeLayoutStore) -> (HomeEditController, FeedHost) {
        let edit = HomeEditController(store: store)
        edit.load(scope: "acc")
        edit.enter()
        let host = FeedHost(edit.layout.shownSections)
        hosts.append(host)
        edit.drag.feedHost = host
        edit.drag.schedulesFrames = false
        return (edit, host)
    }

    /// Lifts [section] and drags its finger from [from] to [to] (viewport y), a few frames apart.
    private func drag(_ edit: HomeEditController, _ section: HomeSection, from: CGFloat, to: CGFloat) {
        edit.drag.liftBlock(section)
        edit.drag.setFingerX(200)
        edit.drag.start(fingerY: from, inList: false)
        edit.drag.frame(dt: 0)
        let steps = 12
        for i in 1...steps {
            edit.drag.move(y: from + (to - from) * CGFloat(i) / CGFloat(steps))
            edit.drag.frame(dt: 0.016)
        }
    }

    func test_should_writeOnceWithOneUndoStep_when_aBlockIsDroppedFurtherDown() {
        let store = tempStore()
        let (edit, _) = editing(store)
        // Activities (100…400) dragged past Jump Back In and Recently Added.
        drag(edit, .activities, from: 250, to: 250 + 318 * 2)
        XCTAssertEqual(edit.order.prefix(3), [.jumpBackIn, .recentlyAdded, .activities], "the others make way while it moves")
        XCTAssertEqual(edit.layout.shownSections.first, .activities, "nothing is written until the drop")
        XCTAssertTrue(edit.undoStack.isEmpty)
        edit.drag.release(cancelled: false)
        XCTAssertEqual(edit.layout.shownSections.prefix(3), [.jumpBackIn, .recentlyAdded, .activities])
        XCTAssertEqual(store.layout("acc").shownSections.prefix(3), [.jumpBackIn, .recentlyAdded, .activities])
        XCTAssertEqual(edit.undoStack.count, 1)
        XCTAssertNil(edit.drag.dragging)
        // Activities left Jump Back In: the document no longer says they pair.
        XCTAssertFalse(store.synced("acc")!.json.contains("span"))
        edit.undo()
        XCTAssertEqual(store.layout("acc").shownSections.first, .activities)
    }

    func test_should_writeNothing_when_droppedWhereItStarted() {
        let store = tempStore()
        let (edit, _) = editing(store)
        drag(edit, .jumpBackIn, from: 568, to: 580)
        edit.drag.release(cancelled: false)
        XCTAssertTrue(edit.undoStack.isEmpty)
        XCTAssertFalse(store.isCustomized("acc"))
    }

    func test_should_keepTheDropOnDone_andRollBackOnBlankTapOrBack() {
        let store = tempStore()
        let (edit, _) = editing(store)
        drag(edit, .activities, from: 250, to: 250 + 318)
        edit.exit(.done)
        XCTAssertFalse(edit.isEditing)
        XCTAssertEqual(store.layout("acc").shownSections.prefix(2), [.jumpBackIn, .activities])

        for reason in [SharedExitReason.blank, .back] {
            let store = tempStore()
            let (edit, _) = editing(store)
            drag(edit, .activities, from: 250, to: 250 + 318)
            XCTAssertEqual(edit.order.prefix(2), [.jumpBackIn, .activities])
            edit.exit(reason)
            XCTAssertEqual(edit.layout.shownSections.first, .activities, "\(reason) rolls back")
            XCTAssertFalse(store.isCustomized("acc"))
            XCTAssertNil(edit.drag.live)
        }
    }

    func test_should_rollBack_when_theFeedResizesGoesToTheBackgroundOrLeaves() {
        let store = tempStore()
        let (edit, _) = editing(store)
        var letGo = 0
        edit.drag.letGo = { letGo += 1 }
        drag(edit, .activities, from: 250, to: 250 + 318)
        edit.cancelDrag()
        XCTAssertEqual(letGo, 1, "the finger still down is swallowed")
        XCTAssertTrue(edit.isEditing, "a resize keeps edit mode")
        XCTAssertEqual(edit.layout.shownSections.first, .activities)
        XCTAssertTrue(edit.undoStack.isEmpty)
        // Home leaves the screen mid-drag.
        drag(edit, .activities, from: 250, to: 250 + 318)
        edit.snapExit()
        XCTAssertFalse(store.isCustomized("acc"))
        XCTAssertNil(edit.drag.liftSection)
    }

    func test_should_holdOtherEdits_when_aDragIsOn() {
        let store = tempStore()
        let (edit, host) = editing(store)
        edit.hide(.rediscover)
        host.order = edit.layout.shownSections
        drag(edit, .activities, from: 250, to: 250 + 318)
        edit.undo()
        edit.reset()
        edit.hide(.yourPlaylists)
        XCTAssertEqual(edit.undoStack.count, 1, "Undo, Reset and Hide wait for the drop")
        edit.drag.release(cancelled: false)
        XCTAssertEqual(edit.undoStack.count, 2)
    }

    func test_should_scrollTheFeed_when_theFingerRestsInTheBottomBand() {
        let store = tempStore()
        let (edit, host) = editing(store)
        drag(edit, .activities, from: 250, to: 860)
        // 72 pt band at the bottom of 0…900: the scroll runs while the finger rests there.
        let before = host.scrolled
        for _ in 0..<30 { edit.drag.frame(dt: 0.016) }
        XCTAssertGreaterThan(host.scrolled, before)
        edit.drag.release(cancelled: false)
        XCTAssertNotEqual(edit.layout.shownSections.first, .activities)
        // The top band scrolls back up.
        let (edit2, host2) = editing(tempStore())
        host2.scrolled = 400
        edit2.drag.liftBlock(.recentlyAdded)
        edit2.drag.start(fingerY: 300, inList: false)
        edit2.drag.move(y: 10)
        for _ in 0..<20 { edit2.drag.frame(dt: 0.016) }
        XCTAssertLessThan(host2.scrolled, 400)
        edit2.drag.release(cancelled: true)
    }

    func test_should_resolvePresses_when_theMachineAsksWhatIsUnderTheFinger() {
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.report(.activities, frame: CGRect(x: 16, y: 100, width: 370, height: 300))
        edit.report(.jumpBackIn, frame: CGRect(x: 16, y: 418, width: 370, height: 300))
        edit.reportHandle(.activities, frame: CGRect(x: 340, y: 90, width: 48, height: 48))
        edit.reportExcluded("hide.activities", frame: CGRect(x: 290, y: 90, width: 48, height: 48))
        edit.reportExcluded(HomeEditController.normalOnly + "header", frame: CGRect(x: 16, y: 0, width: 370, height: 80))
        // Out of edit mode: the header's own holds win; a block is a block, the handle too (it isn't shown).
        XCTAssertTrue(edit.editHit(at: CGPoint(x: 100, y: 40), inList: false) is SharedHitExcluded)
        XCTAssertTrue(edit.editHit(at: CGPoint(x: 360, y: 110), inList: false) is SharedHitBlock)
        let block = edit.editHit(at: CGPoint(x: 100, y: 450), inList: false) as? SharedHitBlock
        XCTAssertEqual(block?.section.id, "jump_back_in")
        XCTAssertEqual(block?.local.y, 32)
        edit.enter()
        XCTAssertTrue(edit.editHit(at: CGPoint(x: 360, y: 110), inList: false) is SharedHitHandle)
        XCTAssertTrue(edit.editHit(at: CGPoint(x: 300, y: 110), inList: false) is SharedHitExcluded, "Hide is a button")
        // Blank header space exits in edit mode; the plate's outset still counts as the block.
        XCTAssertTrue(edit.editHit(at: CGPoint(x: 100, y: 40), inList: false) is SharedHitBlank)
        XCTAssertTrue(edit.editHit(at: CGPoint(x: 10, y: 200), inList: false) is SharedHitBlock)
        let gap = edit.editHit(at: CGPoint(x: 100, y: 407), inList: false) as? SharedHitBlank
        XCTAssertEqual(gap?.nearest?.id, "activities", "the gap between two plates is blank")
        XCTAssertEqual((edit.editHit(at: CGPoint(x: 100, y: 1000), inList: false) as? SharedHitBlank)?.nearest?.id, "jump_back_in")
    }

    func test_should_enterAndLift_when_aTouchHoldsStillOnABlock() {
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.report(.activities, frame: CGRect(x: 16, y: 100, width: 370, height: 300))
        var now: Int64 = 1_000
        let driver = HomeEditGestureDriver(clock: { now }, flinging: { false }) { edit.perform($0, inList: false, finger: CGPoint(x: 100, y: 200)) }
        driver.schedulesTimers = false
        let hit = edit.editHit(at: CGPoint(x: 100, y: 200), inList: false)
        driver.down(hit: hit, at: CGPoint(x: 100, y: 200), editing: edit.isEditing, fling: false, t: now)
        // Half way: the block charges; at T (0.4 s): edit mode, lifted.
        driver.fire()
        XCTAssertEqual(edit.charged, .activities)
        now += 400
        driver.fire()
        XCTAssertTrue(edit.isEditing)
        XCTAssertEqual(edit.drag.liftSection, .activities)
        XCTAssertNil(edit.charged)
        driver.up(t: now + 50)
        XCTAssertTrue(edit.isEditing, "the press's release doesn't leave")
    }

    func test_should_notLift_when_thePressStopsAFling() {
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.enter()
        edit.report(.activities, frame: CGRect(x: 16, y: 100, width: 370, height: 300))
        let driver = HomeEditGestureDriver(clock: { 0 }, flinging: { true }) { edit.perform($0, inList: false, finger: .zero) }
        driver.schedulesTimers = false
        driver.down(hit: edit.editHit(at: CGPoint(x: 100, y: 200), inList: false), at: CGPoint(x: 100, y: 200), editing: true, fling: true, t: 0)
        XCTAssertNil(driver.timeline.untilDue(), "no hold to wait for")
        driver.up(t: 300)
        XCTAssertNil(edit.drag.liftSection)
        XCTAssertTrue(edit.isEditing)
    }

    func test_should_leave_when_blankSpaceIsTapped() {
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.enter()
        let driver = HomeEditGestureDriver(clock: { 0 }, flinging: { false }) { edit.perform($0, inList: false, finger: .zero) }
        driver.schedulesTimers = false
        driver.down(hit: edit.editHit(at: CGPoint(x: 100, y: 2000), inList: false), at: CGPoint(x: 100, y: 2000), editing: true,
                    fling: false, t: 0)
        driver.up(t: 80)
        XCTAssertFalse(edit.isEditing)
    }

    func test_should_foldIntoTheList_when_twoFingersPinchInPastTheThreshold() {
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.enter()
        let driver = HomeEditGestureDriver(clock: { 0 }, flinging: { false }) { edit.perform($0, inList: false, finger: .zero) }
        driver.schedulesTimers = false
        driver.down(hit: SharedHitBlank(nearest: nil), at: CGPoint(x: 100, y: 300), editing: true, fling: false, t: 0)
        driver.pinchDown(span: 200)
        driver.pinchMove(span: 160)
        // 1 − 0.8 over 1 − 0.55: under the 35 % commit, it would spring back.
        XCTAssertEqual(edit.list.progress, 0.2 / 0.45, accuracy: 1e-4)
        driver.pinchMove(span: 110)
        XCTAssertEqual(edit.list.progress, 1, accuracy: 1e-4)
        driver.pinchUp()
        XCTAssertTrue(edit.list.on)
        driver.up(t: 500)
        // Out again: 1.8× the new span unfolds it; the header's switch does it with one hand.
        edit.list.pinchStart()
        edit.list.pinchBy(1.2)
        edit.list.pinchEnd()
        XCTAssertTrue(edit.list.on, "short of 35 % it stays")
        edit.list.toggle()
        XCTAssertFalse(edit.list.on)
        edit.list.toggle()
        XCTAssertTrue(edit.list.on)
        edit.exit(.done)
        XCTAssertFalse(edit.list.on, "Done folds the list back")
    }

    func test_should_notChangeTheList_when_aBlockIsLifted() {
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.enter()
        edit.drag.liftBlock(.activities)
        edit.list.toggle()
        XCTAssertFalse(edit.list.on)
        edit.list.pinchStart()
        edit.list.pinchBy(0.4)
        XCTAssertEqual(edit.list.progress, 0)
    }

    func test_should_scrollToTheTray_when_addIsPressed() {
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.enter()
        let before = edit.trayRequest
        edit.showTray()
        XCTAssertEqual(edit.trayRequest, before + 1)
    }

    func test_should_sayEachBlocksPlace_when_voiceOverReadsEditMode() {
        XCTAssertEqual(HomeEditAccessibility.position(2, of: 9, rows: nil), loc("Section \(2) of \(9)"))
        let rows = HomeRowsHandle.label(4)
        XCTAssertEqual(HomeEditAccessibility.position(1, of: 9, rows: rows), loc("Section \(1) of \(9), \(rows)"))
        XCTAssertNotEqual(HomeEditAccessibility.position(1, of: 9, rows: rows), HomeEditAccessibility.position(1, of: 9, rows: nil))
    }

    func test_should_holdAndLiftAsAndroid_when_timingTheHold() {
        // T = 0.4 s enters; edit mode's body hold is max(150 ms, T/2) (shared HomeEditGestureMachine).
        XCTAssertEqual(HomeEditController.holdDuration(editing: false), 0.4, accuracy: 1e-9)
        XCTAssertEqual(HomeEditController.holdDuration(editing: true), 0.2, accuracy: 1e-9)
    }
}
