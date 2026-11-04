import SwiftUI
import UIKit

/// The album page's motion plumbing: one progress value per axis, moved 1:1
/// by the finger and handed to a spring on release, the spring read every
/// frame (not a SwiftUI `withAnimation`, which would interpolate each view
/// modifier between its two end values instead of the progress — a cover
/// fading out on one page while the other fades in, the "blurry morph").

/// Runs Yoin's spatial spring on one value, frame by frame, carrying the
/// release velocity in. `apply` gets every frame's value.
@MainActor
final class AlbumSpring: NSObject {
    private var link: CADisplayLink?
    private var start: CFTimeInterval = 0
    private var from: CGFloat = 0
    private var delta: CGFloat = 0
    private var velocity: CGFloat = 0
    private var duration: TimeInterval = 0
    private var spring = Spring()
    private var apply: (CGFloat) -> Void = { _ in }
    private var completion: () -> Void = {}

    var isRunning: Bool { link != nil }

    /// Yoin.spatial as a `Spring`: the shared MD3 Expressive token (damping
    /// ratio + stiffness, mass 1) or the iOS default (response 0.42, 0.78).
    static var spatial: Spring {
        if GlassLab.shared.sharedMotion {
            let s = Tokens.motion.expressiveDefaultSpatial
            let k = Double(s.stiffness)
            return Spring(mass: 1, stiffness: k, damping: Double(s.damping) * 2 * k.squareRoot())
        }
        return Spring(response: 0.42, dampingRatio: 0.78)
    }

    /// From `from` to `to`, starting at `velocity` (units per second).
    func run(from: CGFloat, to: CGFloat, velocity: CGFloat, spring: Spring = AlbumSpring.spatial,
             apply: @escaping (CGFloat) -> Void, completion: @escaping () -> Void = {}) {
        stop()
        guard abs(to - from) > 0.0005 || abs(velocity) > 0.01 else {
            apply(to)
            completion()
            return
        }
        self.from = from
        delta = to - from
        self.velocity = velocity
        self.spring = spring
        self.apply = apply
        self.completion = completion
        duration = spring.settlingDuration(target: delta, initialVelocity: velocity, epsilon: 0.0005)
        start = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    /// Stops where it is (a new drag picks the value up from the view's state).
    func stop() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick(_ link: CADisplayLink) {
        let t = link.targetTimestamp - start
        if t >= duration {
            stop()
            apply(from + delta)
            completion()
            return
        }
        apply(from + spring.value(target: delta, initialVelocity: velocity, time: t))
    }
}

/// One pan for the whole album page, axis-locked: the axis is decided from
/// the first movement (the pan's own ~10 pt hysteresis) and only that axis's
/// handler sees the drag, for its whole duration. A drag the page doesn't
/// claim fails at once, so the scroll views under it get it; a drag it does
/// claim is never seen by them (their pans wait for this one to fail).
struct AlbumAxisPan: UIGestureRecognizerRepresentable {
    enum Axis { case horizontal, vertical }

    /// Asked once, at the start: does the page take a drag on this axis
    /// (with this first movement, in points)?
    var claims: (Axis, CGPoint) -> Bool
    /// Translation since the drag was claimed (so the page doesn't jump by the hysteresis).
    var changed: (Axis, CGPoint) -> Void
    /// Release velocity (points per second); `cancelled` when the system took the touch.
    var ended: (Axis, CGPoint, _ cancelled: Bool) -> Void

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var claims: (Axis, CGPoint) -> Bool = { _, _ in false }
        var axis: Axis?
        var origin: CGPoint = .zero

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let pan = g as? UIPanGestureRecognizer else { return false }
            let t = pan.translation(in: pan.view)
            let v = pan.velocity(in: pan.view)
            // The first movement decides; a barely-moved touch goes by its velocity.
            let d = hypot(t.x, t.y) >= 4 ? t : v
            let axis: Axis = abs(d.x) > abs(d.y) ? .horizontal : .vertical
            guard claims(axis, d) else { return false }
            self.axis = axis
            return true
        }

        /// The scroll views inside (scrapbook, track list) wait for the lock:
        /// they move only once the page let the drag go.

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            guard let scroll = other.view as? UIScrollView, other === scroll.panGestureRecognizer,
                  let mine = g.view else { return false }
            return scroll.isDescendant(of: mine)
        }
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.maximumNumberOfTouches = 1
        pan.delegate = context.coordinator
        context.coordinator.claims = claims
        return pan
    }

    func updateUIGestureRecognizer(_ recognizer: UIPanGestureRecognizer, context: Context) {
        context.coordinator.claims = claims
    }

    func handleUIGestureRecognizerAction(_ pan: UIPanGestureRecognizer, context: Context) {
        let c = context.coordinator
        guard let axis = c.axis else { return }
        let t = pan.translation(in: pan.view)
        switch pan.state {
        case .began:
            c.origin = t
            changed(axis, .zero)
        case .changed:
            changed(axis, CGPoint(x: t.x - c.origin.x, y: t.y - c.origin.y))
        case .ended:
            c.axis = nil
            ended(axis, pan.velocity(in: pan.view), false)
        case .cancelled, .failed:
            c.axis = nil
            ended(axis, .zero, true)
        default:
            break
        }
    }
}

/// Whether the visible album page is off its first page: there the edge
/// swipe turns the page back instead of popping the album (the navigation
/// controller's back swipes ask this). Owned by the album page that appeared
/// last, so a page popping out of sight can't clear a newer one's.
@MainActor
enum AlbumPopGate {
    private(set) static var blocksPop = false
    private static var owner: UUID?
    /// The owning page's pager, for the navigation controller's edge pan
    /// (state, translation x, velocity x).
    static var edgeDrag: ((UIGestureRecognizer.State, CGFloat, CGFloat) -> Void)?
    static var edgeOrigin: CGFloat = 0

    static func set(_ blocks: Bool, by id: UUID) {
        owner = id
        blocksPop = blocks
    }

    static func release(by id: UUID) {
        guard owner == id else { return }
        owner = nil
        blocksPop = false
        edgeDrag = nil
    }
}
