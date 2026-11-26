import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass
import YoinShared

/// Home edit's gestures on :shared's machine (Android HomeEditGestures; shared HomeEditGestureMachine,
/// pinned by shared/src/commonTest/resources/golden/home-edit-gestures.json). One finger's events go in
/// at their own `UITouch.timestamp`s (coalesced samples one by one) through `HomeEditGestureTimeline`, its
/// timers wait on the same clock, and the actions come out in order: normal mode only watches until the
/// long press (T), so a tap still opens the card and a slop still scrolls; edit mode lifts a block after
/// the body hold (max(150 ms, T/2)), a handle on its first slop, a tap kicks, a blank tap leaves; a press
/// that stops a fling never lifts; moves stamped before a hold that arrive after it take the lift back;
/// a second finger before any lift pinches the list in or out.
@MainActor
final class HomeEditGestureDriver {
    /// T: the long press that enters edit mode (iOS's own 0.4 s hold, as before).
    nonisolated static let longPressMs: Int64 = 400
    /// The touch slop: the scroll view's own pan starts about here.
    nonisolated static let slop: CGFloat = 10

    let machine: HomeEditGestureMachine
    private(set) var timeline: HomeEditGestureTimeline!
    private let apply: ([HomeEditGestureAction]) -> Void
    private let flinging: () -> Bool
    private let clock: () -> Int64
    private var timer: DispatchWorkItem?
    /// Schedules timers on the main queue; tests turn it off and fire them by hand.
    var schedulesTimers = true

    init(longPressMs: Int64 = HomeEditGestureDriver.longPressMs, slop: CGFloat = HomeEditGestureDriver.slop,
         clock: @escaping () -> Int64 = { HomeEditGestureDriver.ms(CACurrentMediaTime()) },
         flinging: @escaping () -> Bool, apply: @escaping ([HomeEditGestureAction]) -> Void) {
        machine = HomeEditGestureMachine(longPressMs: longPressMs, slopPx: Float(slop))
        self.apply = apply
        self.flinging = flinging
        self.clock = clock
        timeline = HomeEditGestureTimeline(machine: machine, flinging: { [weak self] in
            KotlinBoolean(bool: self?.flinging() ?? false)
        }, step: { [weak self] event in
            guard let self else { return [] }
            let actions = self.machine.handle(event: event)
            if !actions.isEmpty { self.apply(actions) }
            return actions
        })
    }

    /// `UITouch.timestamp` / `CACurrentMediaTime` (s since boot) as the machine's uptime ms.
    nonisolated static func ms(_ seconds: TimeInterval) -> Int64 { Int64((seconds * 1000).rounded()) }

    var state: HomeEditGestureState { machine.state }
    var isIdle: Bool { machine.state == .idle }

    // MARK: Events

    func down(hit: HomeEditHit, at p: CGPoint, editing: Bool, fling: Bool, t: Int64) {
        timeline.start(t: t)
        step(HomeEditGestureEventDown(hit: hit, position: Self.point(p), editing: editing, flingInProgress: fling, t: t,
                                      secondaryClick: false))
    }

    func move(to p: CGPoint, t: Int64) { sample(HomeEditGestureEventMove(position: Self.point(p), t: t)) }
    func up(t: Int64) { sample(HomeEditGestureEventUp(t: t)) }
    func cancel(t: Int64) { sample(HomeEditGestureEventCancel(t: t)) }
    func pinchDown(span: CGFloat) { step(HomeEditGestureEventPinchDown(span: Float(span))) }
    func pinchMove(span: CGFloat) { step(HomeEditGestureEventPinchMove(span: Float(span))) }
    func pinchUp() { step(HomeEditGestureEventPinchUp.shared) }
    func otherPointer() { step(HomeEditGestureEventOtherPointer.shared) }
    func editEnded() { step(HomeEditGestureEventEditEnded.shared) }
    func carryEnded() { step(HomeEditGestureEventCarryEnded.shared) }

    private func step(_ event: HomeEditGestureEvent) {
        let actions = machine.handle(event: event)
        if !actions.isEmpty { apply(actions) }
        reschedule()
    }

    private func sample(_ event: HomeEditGestureEvent) {
        _ = timeline.sample(event: event)
        reschedule()
    }

    // MARK: Timers (on the events' own clock)

    private func reschedule() {
        timer?.cancel()
        timer = nil
        guard schedulesTimers, let due = timeline.untilDue()?.int64Value else { return }
        wait(ms: due) { [weak self] in self?.woke() }
    }

    // The wait for the next timer woke with no input: a late wake (a long frame) gives input held back
    // from before the timer's instant a moment to arrive first.
    private func woke() {
        let grace = timeline.graceAfter(wallMs: clock())
        if grace > 0 {
            wait(ms: grace) { [weak self] in self?.fire() }
        } else {
            fire()
        }
    }

    /// The pending timer fires now (tests call it directly).
    func fire() {
        timeline.timeout()
        reschedule()
    }

    private func wait(ms: Int64, _ body: @escaping () -> Void) {
        let item = DispatchWorkItem(block: body)
        timer = item
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(max(ms, 0))), execute: item)
    }

    func stopTimers() {
        timer?.cancel()
        timer = nil
    }

    private static func point(_ p: CGPoint) -> HomePoint { HomePoint(x: Float(p.x), y: Float(p.y)) }
}

/// What a press lands on in edit terms, answered by Home (HomeEditController.hit).
@MainActor
protocol HomeEditTouchTarget: AnyObject {
    /// The hit at [point] in the list's content (feed) space; [list]: the pinched list asks.
    func editHit(at point: CGPoint, inList list: Bool) -> HomeEditHit
    var isEditing: Bool { get }
    /// Apply the machine's actions (edit mode, the drag, the list, kicks, haptics).
    func perform(_ actions: [HomeEditGestureAction], inList list: Bool, finger: CGPoint)
}

/// The recognizer on the feed's (or the pinched list's) `UIScrollView`: never cancels a touch, runs beside
/// the scroll view's pan, and hands every touch to the driver. While a block is lifted or a pinch runs it
/// holds the pan off, so the list doesn't scroll under the finger (the edge scroll moves it instead).
final class HomeEditTouchRecognizer: UIGestureRecognizer, UIGestureRecognizerDelegate {
    weak var touchTarget: HomeEditTouchTarget?
    /// The anchor view: its space is the list's content (feed) space.
    weak var anchor: UIView?
    var inList = false
    /// The feed was flinging a moment ago (the scroll view's own deceleration ended < 120 ms back).
    var lastFlingEnd: CFTimeInterval = 0

    private var driver: HomeEditGestureDriver!
    private var tracked: UITouch?
    private var second: UITouch?
    private var lastViewport: CGPoint = .zero
    private var panHeld = false

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
        MainActor.assumeIsolated {
            driver = HomeEditGestureDriver(flinging: { [weak self] in self?.isFlinging ?? false }) { [weak self] actions in
                self?.perform(actions)
            }
        }
    }

    private var scrollView: UIScrollView? { view as? UIScrollView }

    /// A press that lands during a fling, or within 120 ms of one ending, only stops it (Android FlingStopMs).
    private var isFlinging: Bool {
        guard let s = scrollView else { return false }
        return s.isDecelerating || CACurrentMediaTime() - lastFlingEnd < 0.12
    }

    // Where a touch is in the viewport (y = 0 at the scroll view's visible top), and in content space.
    private func viewport(_ t: UITouch) -> CGPoint {
        guard let s = scrollView else { return t.location(in: view) }
        let p = t.location(in: s)
        return CGPoint(x: p.x - s.bounds.minX, y: p.y - s.bounds.minY)
    }

    private func viewport(_ p: CGPoint) -> CGPoint {
        guard let s = scrollView else { return p }
        return CGPoint(x: p.x - s.bounds.minX, y: p.y - s.bounds.minY)
    }

    private func span() -> CGFloat {
        guard let a = tracked, let b = second else { return 0 }
        let p = a.location(in: view), q = b.location(in: view)
        return hypot(p.x - q.x, p.y - q.y)
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    // A mouse's secondary click is the card's context menu on iPad and Mac (SecondaryClick.swift): never an edit gesture.
    override func shouldReceive(_ event: UIEvent) -> Bool {
        !event.buttonMask.contains(.secondary)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        // SwiftUI's containers under the scroll view take one touch at a time: the view the first finger
        // landed on must take a second (the pinch) too.
        for t in touches { t.view?.isMultipleTouchEnabled = true }
        MainActor.assumeIsolated {
            // Fingers landing together (a pinch): the one on the page leads, not one on a control.
            let ordered = touches.sorted { a, _ in
                !(touchTarget?.editHit(at: a.location(in: anchor), inList: inList) is HomeEditHitExcluded)
            }
            for touch in ordered {
                if tracked == nil {
                    tracked = touch
                    let p = viewport(touch)
                    lastViewport = p
                    let content = touch.location(in: anchor)
                    let hit = touchTarget?.editHit(at: content, inList: inList) ?? HomeEditHitExcluded.shared
                    let editing = touchTarget?.isEditing ?? false
                    // A handle owns its press: the pan must not start at the same slop that lifts it.
                    if editing, hit is HomeEditHitHandle, !isFlinging { holdPan(true) }
                    driver.down(hit: hit, at: p, editing: editing, fling: isFlinging, t: HomeEditGestureDriver.ms(touch.timestamp))
                } else if second == nil {
                    second = touch
                    driver.pinchDown(span: span())
                } else {
                    driver.otherPointer()
                }
            }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        MainActor.assumeIsolated {
            if let t = tracked, touches.contains(t) {
                // Each sample at its own stamp (a long frame hands several over at once).
                for sample in event.coalescedTouches(for: t) ?? [t] {
                    let p = viewport(sample.location(in: scrollView ?? view))
                    lastViewport = p
                    driver.move(to: p, t: HomeEditGestureDriver.ms(sample.timestamp))
                }
            }
            if second != nil, touches.contains(where: { $0 === tracked || $0 === second }) {
                driver.pinchMove(span: span())
            } else if touches.contains(where: { $0 !== tracked && $0 !== second }) {
                driver.otherPointer()
            }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        MainActor.assumeIsolated { lift(touches, cancelled: false) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        MainActor.assumeIsolated { lift(touches, cancelled: true) }
    }

    @MainActor
    private func lift(_ touches: Set<UITouch>, cancelled: Bool) {
        if let s = second, touches.contains(s) {
            second = nil
            driver.pinchUp()
        }
        if let t = tracked, touches.contains(t) {
            tracked = nil
            let stamp = HomeEditGestureDriver.ms(t.timestamp)
            if cancelled { driver.cancel(t: stamp) } else { driver.up(t: stamp) }
        }
        if tracked == nil && second == nil {
            holdPan(false)
            driver.stopTimers()
            state = state == .possible ? .failed : (cancelled ? .cancelled : .ended)
        }
    }

    override func reset() {
        super.reset()
        tracked = nil
        second = nil
        MainActor.assumeIsolated {
            holdPan(false)
        }
    }

    /// The drag ended from outside (an exit, a resize) while the finger is down: the machine swallows the rest.
    @MainActor func carryEnded() { driver.carryEnded() }

    /// Edit mode ended under the finger.
    @MainActor func editEnded() { driver.editEnded() }

    // Off while a block is lifted or a pinch runs: switching the pan off cancels a scroll already started.
    @MainActor
    private func holdPan(_ on: Bool) {
        guard on != panHeld, let pan = scrollView?.panGestureRecognizer else { return }
        panHeld = on
        pan.isEnabled = !on
    }

    @MainActor
    private func perform(_ actions: [HomeEditGestureAction]) {
        for a in actions {
            switch a {
            case is HomeEditGestureActionLift, is HomeEditGestureActionPinchStart: holdPan(true)
            case is HomeEditGestureActionAbandon:
                // Normal mode gave the press up: the page has it (edit mode keeps watching for a pinch).
                if touchTarget?.isEditing != true { state = .failed }
            default: break
            }
        }
        touchTarget?.perform(actions, inList: inList, finger: lastViewport)
    }
}

/// Home's touch anchor (it replaces the old long-press `HomeHoldObserver`): a view set behind the feed's
/// (or the pinched list's) stack, so its space is the stack's. It finds the enclosing `UIScrollView`, puts
/// the edit recognizer on it, and answers the drag as its host: the rows laid out for an order (from the
/// blocks' measured heights and the stack's gap, so a new order is laid out the moment it is decided), the
/// content's left and width, the scroll area and the scroll itself.
struct HomeEditTouchView: UIViewRepresentable {
    var edit: HomeEditController
    var inList = false

    func makeUIView(context: Context) -> AnchorView { AnchorView() }

    func updateUIView(_ v: AnchorView, context: Context) {
        v.edit = edit
        v.inList = inList
        v.recognizer.inList = inList
        v.recognizer.touchTarget = edit
        if inList { edit.drag.listHost = v } else { edit.drag.feedHost = v }
        if inList { edit.listRecognizer = v.recognizer } else { edit.feedRecognizer = v.recognizer }
    }

    final class AnchorView: UIView, HomeDragHost {
        weak var edit: HomeEditController?
        var inList = false
        let recognizer = HomeEditTouchRecognizer(target: nil, action: nil)
        /// A trackpad's pinch (iPad with a trackpad, Mac): no touches reach the recognizer, the system hands a scale.
        let trackpadPinch = UIPinchGestureRecognizer()
        private var decelerating: NSKeyValueObservation?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            recognizer.anchor = self
            trackpadPinch.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.indirectPointer.rawValue)]
            trackpadPinch.cancelsTouchesInView = false
            trackpadPinch.delegate = recognizer
            trackpadPinch.addTarget(self, action: #selector(pinched(_:)))
        }

        @objc private func pinched(_ g: UIPinchGestureRecognizer) {
            guard let edit, edit.isEditing || g.state != .began else { return }
            switch g.state {
            case .began: edit.list.pinchStart()
            case .changed: edit.list.pinchBy(g.scale)
            case .ended, .cancelled, .failed: edit.list.pinchEnd()
            default: break
            }
        }

        required init?(coder: NSCoder) { fatalError() }

        private var scrollView: UIScrollView? {
            var v: UIView? = superview
            while let cur = v, !(cur is UIScrollView) { v = cur.superview }
            return v as? UIScrollView
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil, let s = scrollView else { return }
            Self.takeEveryFinger(s)
            guard recognizer.view == nil else { return }
            s.addGestureRecognizer(recognizer)
            s.addGestureRecognizer(trackpadPinch)
            // When a fling ends (a press within 120 ms of it only stops it).
            decelerating = s.observe(\.contentOffset, options: []) { [weak self] s, _ in
                MainActor.assumeIsolated {
                    if s.isDecelerating { self?.recognizer.lastFlingEnd = CACurrentMediaTime() }
                }
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            if let s = scrollView { Self.takeEveryFinger(s) }
        }

        // SwiftUI's containers under the scroll view take one touch at a time (UIView's default): a second
        // finger landing on them (the pinch) would never reach the recognizer.
        private static func takeEveryFinger(_ s: UIScrollView) {
            s.isMultipleTouchEnabled = true
            for sub in s.subviews {
                sub.isMultipleTouchEnabled = true
                for inner in sub.subviews { inner.isMultipleTouchEnabled = true }
            }
        }

        // MARK: HomeDragHost

        var isList: Bool { inList }

        var order: [HomeSection] { MainActor.assumeIsolated { edit?.dragOrder(inList: inList) ?? [] } }

        /// The stack's top in the scroll view's content, so feed-space y = viewport y + viewportTop.
        var viewportTop: CGFloat {
            guard let s = scrollView else { return 0 }
            return s.bounds.minY - convert(CGPoint.zero, to: s).y
        }

        func bands(for order: [HomeSection]) -> [(section: HomeSection, top: CGFloat, bottom: CGFloat)] {
            MainActor.assumeIsolated {
                guard let edit else { return [] }
                let top = viewportTop
                return edit.layoutBands(for: order, inList: inList).map { ($0.section, $0.top - top, $0.bottom - top) }
            }
        }

        var contentLeft: CGFloat { MainActor.assumeIsolated { edit?.contentBand(inList: inList).minX ?? 0 } }
        var contentWidth: CGFloat { MainActor.assumeIsolated { edit?.contentBand(inList: inList).width ?? bounds.width } }

        var scrollArea: (top: CGFloat, bottom: CGFloat) {
            guard let s = scrollView else { return (0, bounds.height) }
            return (s.safeAreaInsets.top, s.bounds.height - s.safeAreaInsets.bottom)
        }

        func scrollBy(_ dy: CGFloat) -> CGFloat {
            guard let s = scrollView else { return 0 }
            let inset = s.adjustedContentInset
            let minY = -inset.top
            let maxY = max(minY, s.contentSize.height + inset.bottom - s.bounds.height)
            let from = s.contentOffset.y
            let to = min(max(from + dy, minY), maxY)
            guard to != from else { return 0 }
            s.contentOffset.y = to
            return to - from
        }
    }
}
