import SwiftUI
import UIKit

/*
 * Memories' one gesture router (Android MemoriesGestures). Every drag on the
 * page goes through it; at the slop it picks ONE axis and, for a vertical
 * drag, ONE controller, never two:
 *
 *  · horizontal → the deck's pager (the router only pauses the award's beats);
 *  · top bar, up (either state) → q on the bar rule (56 pt / 450 pt/s);
 *  · top bar, down, diary open → p, the diary's handle: 1:1;
 *  · card body, up → q on the body rule (112 pt / 600 pt/s);
 *  · card body, down → p's rubber band below the card (0.3×, −90 pt), capped at the card;
 *  · diary body → the diary's own scroll (a pull past its top feeds p, see
 *    DiaryScrollObserver), except a new upward drag that starts with the diary
 *    resting at its end: q on the body rule;
 *  · a title being edited holds every vertical drag: nothing moves q or p.
 *
 * q is MemoriesPresentation and p is MemoriesDiary: the router only feeds
 * them, it owns no displacement of its own. The page follows the finger from
 * the lock on (the slop is never applied as a jump); the release rule is
 * shortened by the travel the slop ate instead.
 */

/// Where a vertical drag started.
enum MemoriesDragZone: Equatable { case bar, card, diary }

/// The axis a drag locks to once it has travelled the slop.
enum MemoriesDragAxis: Equatable { case horizontal, vertical }

/// Which controller a vertical drag feeds, decided once at the slop.
enum MemoriesVerticalRoute: Equatable {
    /// q, the retreat to Home; [fromBar] picks the bar rule, else the body's.
    case dismiss(fromBar: Bool)
    /// The card's own pull-down: p below 0 on its rubber band.
    case cardRubberBand
    /// The diary's handle: a pull down on the bar scrubs p 1:1.
    case diaryHandle
    /// The diary's text scrolls (its observer hands an overflow past the top to p).
    case diaryScroll
    /// A title is being edited: neither q nor p moves.
    case held
}

enum MemoriesRouting {
    /// Nil until the drag has travelled [slop]; then the axis, ties going vertical.
    static func axis(dx: CGFloat, dy: CGFloat, slop: CGFloat = MemoriesBackTokens.touchSlop) -> MemoriesDragAxis? {
        if hypot(dx, dy) < slop { return nil }
        return abs(dx) > abs(dy) ? .horizontal : .vertical
    }

    /// The controller for a vertical drag from [zone] whose travel at the slop is [deltaY] (negative = up).
    static func route(zone: MemoriesDragZone, deltaY: CGFloat, cardPresent: Bool, diaryLevel: Bool = false,
                      diaryAtEnd: Bool = false, titleEditing: Bool = false) -> MemoriesVerticalRoute {
        if titleEditing { return .held }
        if zone == .bar && diaryLevel && deltaY > 0 { return .diaryHandle }
        if zone == .bar { return .dismiss(fromBar: true) }
        if zone == .diary && diaryAtEnd && deltaY < 0 { return .dismiss(fromBar: false) }
        if zone == .diary { return .diaryScroll }
        if deltaY < 0 || !cardPresent { return .dismiss(fromBar: false) }
        return .cardRubberBand
    }
}

/// A drag against one threshold: [update] is true on the frame the value
/// crosses it, either way (the CLOCK_TICK lands as a release would start to
/// commit, and again if the finger takes it back).
struct ThresholdCrossing {
    let threshold: CGFloat
    private(set) var isPast: Bool

    init(threshold: CGFloat, initial: CGFloat) {
        self.threshold = threshold
        isPast = initial >= threshold
    }

    mutating func update(_ value: CGFloat) -> Bool {
        let now = value >= threshold
        guard now != isPast else { return false }
        isPast = now
        return true
    }
}

/// The router: the page geometry it reads at gesture time and the two
/// controllers it feeds. Plain state (never read by a view body).
@MainActor
final class MemoriesGestureRouter {
    let reveal: MemoriesPresentation
    let diary: MemoriesDiary
    /// The award lifecycle of this open: told about presses, lifts and drags.
    var awards: MemoriesAwardLifecycle?
    /// A card face is on screen (no deck: every vertical drag is q).
    var cardPresent = false
    /// A spread: no card face to rubber-band, no diary level.
    var isSpread = false
    var titleEditing: () -> Bool = { false }
    /// The open diary rests at its very end (set by the current page's observer).
    var diaryAtEnd: () -> Bool = { false }
    /// A drag crossed its commit line (and again going back): CLOCK_TICK.
    var onThresholdCrossed: () -> Void = { MemoriesHaptics.tick() }
    /// A release committed the retreat: CONFIRM.
    var onCommitted: () -> Void = { MemoriesHaptics.confirm() }

    private var slopUp: CGFloat = 0
    private var lockY: CGFloat = 0
    private var lastY: CGFloat = 0
    private var qCrossing: ThresholdCrossing?
    private var pCrossing: ThresholdCrossing?
    private var atEndAtDown = false

    init(reveal: MemoriesPresentation, diary: MemoriesDiary) {
        self.reveal = reveal
        self.diary = diary
    }

    func press() {
        awards?.onFingerDown()
        atEndAtDown = diaryAtEnd()
    }

    func lift() { awards?.onFingerUp() }

    func beginHorizontal() { awards?.onDragStart() }

    /// The drag locked vertical from [zone] at [translationY] (since touch-down).
    func begin(zone: MemoriesDragZone, translationY: CGFloat) -> MemoriesVerticalRoute {
        let route = MemoriesRouting.route(zone: zone, deltaY: translationY, cardPresent: cardPresent && !isSpread,
                                          diaryLevel: !isSpread && diary.isDiaryLevel, diaryAtEnd: atEndAtDown,
                                          titleEditing: titleEditing())
        slopUp = -translationY
        lockY = translationY
        lastY = translationY
        qCrossing = nil
        pCrossing = nil
        switch route {
        case .dismiss(let fromBar):
            let rule = (fromBar ? MemoriesDismissRule.bar : .body).afterSlop(slopUp)
            qCrossing = ThresholdCrossing(threshold: rule.commit, initial: reveal.fraction * reveal.containerHeight)
        case .cardRubberBand:
            diary.startPull(banded: false, fromScrolled: false, ceiling: 0)
        case .diaryHandle:
            diary.startPull(banded: false, fromScrolled: false)
            pCrossing = ThresholdCrossing(threshold: MemoriesBackTokens.diaryCloseThreshold, initial: 1 - diary.fraction)
        case .diaryScroll, .held:
            break
        }
        awards?.onDragStart()
        return route
    }

    /// The finger is at [translationY] since touch-down (positive = down).
    func drag(_ route: MemoriesVerticalRoute, translationY: CGFloat) {
        let delta = translationY - lastY
        lastY = translationY
        switch route {
        case .dismiss(let fromBar):
            reveal.dismissDrag(-(translationY - lockY), rule: (fromBar ? MemoriesDismissRule.bar : .body).afterSlop(slopUp))
            if qCrossing?.update(reveal.fraction * reveal.containerHeight) == true { onThresholdCrossed() }
        case .cardRubberBand:
            diary.pullBy(delta)
        case .diaryHandle:
            diary.pullBy(delta)
            if pCrossing?.update(1 - diary.fraction) == true { onThresholdCrossed() }
        case .diaryScroll, .held:
            break
        }
    }

    /// Released at [velocityY] pt/s (positive = down).
    func release(_ route: MemoriesVerticalRoute, velocityY: CGFloat) {
        switch route {
        case .dismiss(let fromBar):
            let rule = (fromBar ? MemoriesDismissRule.bar : .body).afterSlop(slopUp)
            if reveal.dismissEnded(velocity: -velocityY, rule: rule) {
                awards?.onDismissCommitted()
                onCommitted()
            }
        case .cardRubberBand, .diaryHandle:
            diary.releasePull(velocity: velocityY)
        case .diaryScroll, .held:
            break
        }
    }
}

/// Memories' haptics: CLOCK_TICK at a commit line, CONFIRM on a commit, a click on a button.
@MainActor
enum MemoriesHaptics {
    private static let rigid = UIImpactFeedbackGenerator(style: .rigid)
    private static let medium = UIImpactFeedbackGenerator(style: .medium)
    private static let light = UIImpactFeedbackGenerator(style: .light)

    static func tick() { rigid.impactOccurred(intensity: 0.55) }
    static func confirm() { medium.impactOccurred(intensity: 0.8) }
    static func click() { light.impactOccurred(intensity: 0.7) }
    /// A soft beat at [scale] (a track row, a nod).
    static func soft(_ scale: CGFloat) { light.impactOccurred(intensity: scale) }
}

// MARK: - The diary's scroll as an input

/// The diary text's pull (Android MemoriesDiaryDeck.connection), on the diary
/// ScrollView's own pan: while p < 1 the finger moves p (either way) before the
/// text; at p 1 the text scrolls, and only a pull past its top reaches p
/// (banded: its first 24 pt at half speed). The offset stays pinned while p
/// has the finger, so the text never bounces under it. A new upward drag
/// that starts with the text resting at its end is q's (the router's body rule).
struct DiaryScrollObserver: UIViewRepresentable {
    var router: MemoriesGestureRouter
    /// This page's diary is the open one (input only then).
    var active: Bool
    /// A spread's right page: no p, only the retreat from its end.
    var spread: Bool

    func makeUIView(context: Context) -> ObserverView { ObserverView() }

    func updateUIView(_ v: ObserverView, context: Context) {
        v.router = router
        v.active = active
        v.spread = spread
        v.attach()
    }

    final class ObserverView: UIView {
        var router: MemoriesGestureRouter?
        var active = false
        var spread = false
        private weak var scrollView: UIScrollView?
        private enum Mode { case none, pull, dismiss }
        private var mode = Mode.none
        private var scrolledAtDown = false
        private var atEndAtDown = false
        private var last: CGFloat = 0
        private var start: CGFloat = 0
        private var closeLine: ThresholdCrossing?
        private var dismissRoute: MemoriesVerticalRoute?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { fatalError() }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            attach()
        }

        func attach() {
            guard window != nil, scrollView == nil else { return }
            var v: UIView? = superview
            while let cur = v, !(cur is UIScrollView) { v = cur.superview }
            guard let sv = v as? UIScrollView else { return }
            scrollView = sv
            sv.panGestureRecognizer.addTarget(self, action: #selector(pan(_:)))
        }

        private func top(_ sv: UIScrollView) -> CGFloat { -sv.adjustedContentInset.top }
        private func end(_ sv: UIScrollView) -> CGFloat {
            max(top(sv), sv.contentSize.height + sv.adjustedContentInset.bottom - sv.bounds.height)
        }

        /// The text rests at its end, nothing moving (the router's q-at-end rule).
        var restsAtEnd: Bool {
            guard let sv = scrollView, let router, router.diary.fraction >= 0.999, !router.diary.isSettling else { return false }
            return !sv.isDecelerating && sv.contentOffset.y >= end(sv) - 1
        }

        @objc private func pan(_ g: UIPanGestureRecognizer) {
            guard let sv = scrollView, let router, active else { return }
            let ty = g.translation(in: nil).y
            let diary = router.diary
            switch g.state {
            case .began:
                router.press()
                mode = .none
                last = ty
                start = ty
                scrolledAtDown = sv.contentOffset.y > top(sv) + 0.5
                atEndAtDown = restsAtEnd
            case .changed:
                let delta = ty - last
                last = ty
                if router.titleEditing() { return }
                switch mode {
                case .none:
                    if atEndAtDown, ty - start < 0 {
                        // From the end, up: the retreat (body rule, the slop already eaten).
                        mode = .dismiss
                        dismissRoute = router.begin(zone: .diary, translationY: ty - start)
                        sv.contentOffset.y = end(sv)
                        return
                    }
                    guard !spread else { return }
                    let atTop = sv.contentOffset.y <= top(sv) + 0.5
                    if (delta > 0 && atTop) || (diary.fraction < 1 && delta != 0) {
                        mode = .pull
                        diary.startPull(banded: true, fromScrolled: scrolledAtDown)
                        closeLine = ThresholdCrossing(threshold: MemoriesBackTokens.diaryCloseThreshold,
                                                      initial: 1 - diary.fraction)
                        pull(delta, sv)
                    }
                case .pull:
                    pull(delta, sv)
                case .dismiss:
                    sv.contentOffset.y = end(sv)
                    if let r = dismissRoute { router.drag(r, translationY: ty - start) }
                }
            case .ended, .cancelled, .failed:
                router.lift()
                let v = g.state == .ended ? g.velocity(in: nil).y : 0
                switch mode {
                case .pull:
                    sv.contentOffset.y = top(sv)
                    if diary.fraction < 1 { diary.releasePull(velocity: v) }
                    // A fling that would scroll on stops at the top instead.
                    sv.setContentOffset(CGPoint(x: 0, y: top(sv)), animated: false)
                case .dismiss:
                    if let r = dismissRoute { router.release(r, velocityY: v) }
                case .none:
                    break
                }
                mode = .none
                dismissRoute = nil
                closeLine = nil
            default:
                break
            }
        }

        private func pull(_ delta: CGFloat, _ sv: UIScrollView) {
            guard let router else { return }
            let diary = router.diary
            diary.pullBy(delta)
            if closeLine?.update(1 - diary.fraction) == true { router.onThresholdCrossed() }
            if diary.fraction >= 1 {
                // Back at the top of the text: the text has the finger again.
                mode = .none
                closeLine = nil
            } else {
                sv.contentOffset.y = top(sv)
            }
        }
    }
}
