import SwiftUI
import UIKit
import YoinShared

/// Memories (ShellOverlayUp): one reveal fraction q drives it all, Android's
/// RevealState convention — 1 = retreated above Home, 0 = in place. The pull
/// down on Home moves it 1:1 and lets go with the finger's velocity; inside
/// Memories the drag up moves the same q 1:1 and commits by Android's
/// dismiss rule (112 pt, 600 pt/s, a 350 pt/s flick back returns); the ⌃ Home
/// pill, the bar's Home tap and VoiceOver's escape spring the same q. Every
/// layer reads q (Home behind, the page's offset, the arrow's hint): one
/// displacement, never a second state.
@MainActor
@Observable
final class MemoriesPresentation {
    /// 1 = retreated above Home, 0 = in place; past 1 only while pulled the wrong way (rubber band).
    private(set) var fraction: CGFloat = 1 { didSet { updateMounted() } }
    /// Any of it is on screen (or about to be). Stored, so a frame of q
    /// invalidates only its readers, and this flips twice per motion.
    private(set) var mounted = false
    /// A drag holds q (Home's pull or Memories' own drag).
    private(set) var dragging = false
    /// Where the spring is going (0 open, 1 closed).
    private(set) var target: CGFloat = 1 { didSet { updateMounted() } }
    /// Opened (or opening) on a memory: the deck stops on this album.
    private(set) var focusAlbumId: String?
    /// Bumped on every open, so the deck rebuilds its pool (Android: a focus
    /// tap always rebuilds; an empty pool is never cached).
    private(set) var openCount = 0
    /// The page's height: q's 1:1 scale.
    @ObservationIgnored var containerHeight: CGFloat = 800
    /// The commit distance (pt) of whatever drives q now: the bottom corners
    /// are full there. The bar's or the body's rule (less the slop) while
    /// Memories' own drag holds q; the body's otherwise.
    @ObservationIgnored private(set) var cornerThreshold: CGFloat = dismissCommit

    @ObservationIgnored private let spring = SpringRunner()
    @ObservationIgnored private var dragFrom: CGFloat = 1

    private func updateMounted() {
        let m = fraction < 1 - Self.visibleEpsilon || target == 0
        if m != mounted { mounted = m }
    }
    /// At rest, open.
    var isOpen: Bool { target == 0 }

    nonisolated static let visibleEpsilon: CGFloat = 0.001
    /// RevealState defaults.
    nonisolated static let rubberBand: CGFloat = 0.30
    nonisolated static let velocityThreshold: CGFloat = 1.6
    nonisolated static let positionThreshold: CGFloat = 0.5
    /// BackMotionTokens: the retreat from the card body.
    nonisolated static let dismissCommit: CGFloat = 112
    nonisolated static let dismissFling: CGFloat = 600
    nonisolated static let flickBack: CGFloat = 350

    // MARK: Pure rules (unit-tested)

    /// The page's bottom corner radius (Android MemoriesBackMath.cornerRadius):
    /// [maxRadius] · smoothstep(0, threshold / H, q), full exactly when a
    /// release would commit; square at rest open.
    nonisolated static func cornerRadius(fraction q: CGFloat, threshold: CGFloat, height: CGFloat,
                                         maxRadius: CGFloat = MemoriesBackTokens.pageCornerRadius) -> CGFloat {
        if q <= 0 { return 0 }
        if height <= 0 || threshold <= 0 { return maxRadius }
        let t = min(max(q / (threshold / height), 0), 1)
        return maxRadius * t * t * (3 - 2 * t)
    }

    /// RevealState.applyRubberBand: hard at the open end, soft past closed.
    nonisolated static func rubberBanded(_ raw: CGFloat, c: CGFloat = rubberBand) -> CGFloat {
        if raw <= 0 { return 0 }
        if raw <= 1 { return raw }
        let over = raw - 1
        return 1 + c * (1 - 1 / (1 + over / c))
    }

    /// RevealState.chooseTarget: velocity in fraction / s (negative = opening).
    nonisolated static func settleTarget(_ current: CGFloat, velocity v: CGFloat) -> CGFloat {
        if v <= -velocityThreshold { return 0 }
        if v >= velocityThreshold { return 1 }
        return current < positionThreshold ? 0 : 1
    }

    /// chooseDismissTarget: [dismissed] travel up (pt) and its speed (pt/s, up
    /// positive), on the card body's rule unless given the bar's.
    nonisolated static func dismissTarget(dismissed: CGFloat, velocity: CGFloat,
                                          commit: CGFloat = dismissCommit, fling: CGFloat = dismissFling) -> CGFloat {
        if velocity <= -flickBack { return 0 }
        if dismissed >= commit || velocity >= fling { return 1 }
        return 0
    }

    // MARK: Home's pull

    /// The finger pulled [pull] pt down from where the pull engaged (0 = at rest, negative = back up).
    func pull(_ pull: CGFloat) {
        if !dragging {
            spring.stop()
            dragging = true
            cornerThreshold = Self.dismissCommit
            dragFrom = min(fraction, 1)
        }
        let raw = dragFrom - pull / max(containerHeight, 1)
        fraction = Self.rubberBanded(raw)
    }

    /// Released at [velocity] pt/s (down positive). Returns true when it opens.
    @discardableResult
    func pullEnded(velocity: CGFloat) -> Bool {
        guard dragging else { return false }
        dragging = false
        let v = -velocity / max(containerHeight, 1)
        let to = Self.settleTarget(fraction, velocity: v)
        if to == 0 { openCount += 1; focusAlbumId = nil }
        run(to: to, velocity: v)
        return to == 0
    }

    // MARK: Memories' own drag (up = retreat)

    /// The finger moved [up] pt up from where the drag started, on [rule]
    /// (the release's: it sets where the corners are full).
    func dismissDrag(_ up: CGFloat, rule: MemoriesDismissRule = .body) {
        if !dragging {
            spring.stop()
            dragging = true
            cornerThreshold = rule.commit
            dragFrom = max(fraction, 0)
        }
        fraction = Self.rubberBanded(dragFrom + up / max(containerHeight, 1))
    }

    /// Released at [velocity] pt/s (up positive) on [rule] (the body's, the
    /// bar's; less the slop the lock ate). Returns true when it retreats.
    @discardableResult
    func dismissEnded(velocity: CGFloat, rule: MemoriesDismissRule = .body) -> Bool {
        guard dragging else { return false }
        dragging = false
        let to = Self.dismissTarget(dismissed: fraction * containerHeight, velocity: velocity,
                                    commit: rule.commit, fling: rule.fling)
        let v = velocity / max(containerHeight, 1)
        // A commit never starts moving backwards.
        run(to: to, velocity: to == 1 ? max(v, 0) : v)
        return to == 1
    }

    // MARK: Buttons, taps, back

    /// Open (from the arrow, the bubble, a memory card), stopped on [focus] when given.
    func open(focus albumId: String? = nil) {
        focusAlbumId = albumId
        openCount += 1
        dragging = false
        cornerThreshold = Self.dismissCommit
        run(to: 0, velocity: nil)
    }

    /// Retreat to Home (the ⌃ Home pill, the bar's Home, escape).
    func close() {
        dragging = false
        cornerThreshold = Self.dismissCommit
        run(to: 1, velocity: nil)
    }

    /// Home went off screen: covered by a pushed page while it is still the
    /// section (Android keeps the Memories session under Go to album), or
    /// left (a section switch, anything else), where Memories snaps closed.
    nonisolated static func survivesHomeHidden(onHome: Bool, pushed: Bool) -> Bool { onHome && pushed }

    /// Gone at once, unseen (Home leaves the screen with the section switch).
    func snapClosed() {
        spring.stop()
        dragging = false
        target = 1
        fraction = 1
    }

    private func run(to: CGFloat, velocity: CGFloat?) {
        let v = velocity ?? (spring.isRunning ? spring.currentVelocity : 0)
        target = to
        spring.run(from: fraction, to: to, velocity: v, spring: Self.settleSpring) { [weak self] value in
            self?.fraction = value
        } completion: { [weak self] in
            self?.fraction = to
        }
    }

    /// Android predictiveBackSettleSpring: DampingRatioLowBouncy (0.75) at
    /// StiffnessMediumLow (400) × 1.3. Slowed by `-animationSpeed` for review.
    static var settleSpring: Spring {
        let speed = LaunchConfig.animationSpeed
        let k = 520 * speed * speed
        return Spring(mass: 1, stiffness: k, damping: 0.75 * 2 * k.squareRoot())
    }
}

/// One value on a spring, frame by frame (the clock clamps late frames like
/// NowPlayingSpring, so a busy first frame slows the motion instead of
/// skipping it). `animate` returns when it lands (true) or is replaced (false).
@MainActor
final class SpringRunner: NSObject {
    private var link: CADisplayLink?
    private var elapsed: CFTimeInterval = 0
    private var lastFrame: CFTimeInterval = 0
    private var started = false
    private var from: CGFloat = 0
    private var delta: CGFloat = 0
    private var velocity: CGFloat = 0
    private var duration: TimeInterval = 0
    private var spring = Spring()
    private var apply: (CGFloat) -> Void = { _ in }
    private var completion: () -> Void = {}
    private var waiter: CheckedContinuation<Bool, Never>?

    var isRunning: Bool { link != nil }
    private(set) var currentVelocity: CGFloat = 0

    func run(from: CGFloat, to: CGFloat, velocity: CGFloat, spring: Spring,
             apply: @escaping (CGFloat) -> Void, completion: @escaping () -> Void = {}) {
        stop()
        self.from = from
        delta = to - from
        self.velocity = velocity
        self.spring = spring
        self.apply = apply
        self.completion = completion
        currentVelocity = velocity
        guard abs(delta) > 0.0005 || abs(velocity) > 0.01 else {
            apply(to)
            finish(true)
            return
        }
        duration = spring.settlingDuration(target: delta, initialVelocity: velocity, epsilon: 0.0005)
        started = false
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
    }

    /// Runs and waits: true = landed, false = stopped or replaced first.
    func animate(from: CGFloat, to: CGFloat, velocity: CGFloat = 0, spring: Spring,
                 apply: @escaping (CGFloat) -> Void) async -> Bool {
        await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            run(from: from, to: to, velocity: velocity, spring: spring, apply: apply)
            if link == nil {
                c.resume(returning: true)
            } else {
                waiter = c
            }
        }
    }

    func stop() {
        link?.invalidate()
        link = nil
        let w = waiter
        waiter = nil
        w?.resume(returning: false)
    }

    private func finish(_ landed: Bool) {
        completion()
        let w = waiter
        waiter = nil
        w?.resume(returning: landed)
    }

    @objc private func tick(_ l: CADisplayLink) {
        if !started {
            started = true
            elapsed = 0
            lastFrame = l.targetTimestamp
        } else {
            elapsed += min(l.targetTimestamp - lastFrame, 1.0 / 30)
            lastFrame = l.targetTimestamp
        }
        let t = elapsed + (l.targetTimestamp - l.timestamp)
        if t >= duration {
            link?.invalidate()
            link = nil
            currentVelocity = 0
            apply(from + delta)
            finish(true)
            return
        }
        currentVelocity = spring.velocity(target: delta, initialVelocity: velocity, time: t)
        apply(from + spring.value(target: delta, initialVelocity: velocity, time: t))
    }
}

extension Spring {
    /// A shared YoinTokens spring (damping ratio + stiffness), slowed by `-animationSpeed`.
    @MainActor static func yoin(_ s: YoinTokens.MotionSpring) -> Spring {
        let speed = LaunchConfig.animationSpeed
        let k = s.stiffness * speed * speed
        return Spring(mass: 1, stiffness: k, damping: s.damping * 2 * k.squareRoot())
    }
}
