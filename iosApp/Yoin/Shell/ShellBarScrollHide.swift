import QuartzCore
import SwiftUI

/// Hides the shell's bottom bar while the Home feed scrolls down and brings it
/// back on the way up (PR #247 / Android `ShellBarScrollHide` / Harmony `ShellBarScrollHide`).
///
/// One normalized progress `fraction` (`0` = shown, `1` = hidden) fed by two inputs:
/// 1. Live feed scroll deltas inside scrollable bounds (`scrollHideDelta`), plus pulls past the top edge.
/// 2. Release velocity / position settle via `SpringRunner` (`Yoin.spatial` / standard spatial spring).
@MainActor
@Observable
final class ShellBarScrollHide {
    /// Logical scroll travel (pt) required to take the bar from fully shown to fully hidden.
    static let hideDistance: CGFloat = 96

    /// Velocity (pt/s) past which a release decides the target end over midpoint position.
    static let flingDecisionVelocity: CGFloat = 300

    /// 0 = bar shown, 1 = bar hidden off the bottom edge.
    private(set) var fraction: CGFloat = 0

    /// Only true while on the plain Home feed with the bottom bar visible (not edit mode, Memories,
    /// Now Playing, Search, pushed pages, etc.). Disabling automatically springs the bar back to `0`.
    var enabled: Bool = true {
        didSet {
            guard enabled != oldValue else { return }
            if !enabled {
                resetGesture()
                settle(to: 0)
            }
        }
    }

    @ObservationIgnored private let runner = SpringRunner()
    @ObservationIgnored private var lastScrollOffset: CGFloat?
    @ObservationIgnored private var lastScrollTime: CFTimeInterval?
    @ObservationIgnored private var smoothedVelocity: CGFloat = 0
    @ObservationIgnored private var isInteracting = false
    @ObservationIgnored private var spring: Spring { .yoin(Tokens.motion.standardDefaultSpatial) }

    /// Computes how much of a scroll from `oldOffset` to `newOffset` (within `0 ... maxOffset`)
    /// counts toward hiding (`> 0`) or revealing (`< 0`). Bounces past bottom or back from either
    /// edge bounce are excluded so content too short to scroll never hides the bar.
    nonisolated static func scrollHideDelta(
        from oldOffset: CGFloat,
        to newOffset: CGFloat,
        maxOffset: CGFloat
    ) -> CGFloat {
        let lo: CGFloat = 0
        let hi = max(lo, maxOffset)
        let clampedOld = min(max(oldOffset, lo), hi)
        let clampedNew = min(max(newOffset, lo), hi)
        var delta = clampedNew - clampedOld
        // Further pull past the top edge (`newOffset < oldOffset <= 0`) always reveals;
        // snapping back from overscroll (`newOffset > oldOffset` while `<= 0`) must not hide.
        let pastTop = min(0, newOffset - lo) - min(0, oldOffset - lo)
        if pastTop < 0 {
            delta += pastTop
        }
        return delta
    }

    /// Feeds an already-filtered scroll delta (positive = scrolling down toward hiding).
    func scrolled(by hideDelta: CGFloat, timestamp: CFTimeInterval? = nil) {
        guard enabled, hideDelta != 0 else { return }
        runner.stop()
        let next = min(max(fraction + hideDelta / Self.hideDistance, 0), 1)
        if abs(next - fraction) > 0.0001 || (next == 0 && fraction != 0) || (next == 1 && fraction != 1) {
            fraction = next
        }

        let now = timestamp ?? CACurrentMediaTime()
        if let prev = lastScrollTime {
            let dt = now - prev
            if dt > 0.001, dt < 0.25 {
                let instant = hideDelta / CGFloat(dt)
                smoothedVelocity = smoothedVelocity * 0.45 + instant * 0.55
            } else if dt >= 0.25 {
                smoothedVelocity = 0
            }
        }
        lastScrollTime = now
    }

    /// Feeds SwiftUI scroll geometry changes from the Home feed.
    func handleScrollOffsetChange(
        oldOffset: CGFloat,
        newOffset: CGFloat,
        maxOffset: CGFloat,
        timestamp: CFTimeInterval? = nil
    ) {
        lastScrollOffset = newOffset
        guard enabled else { return }
        if newOffset <= 0.5, oldOffset > 0.5, fraction > 0, !isInteracting {
            // Non-interactive jump back to the top (e.g. status-bar tap).
            show()
            return
        }
        let delta = Self.scrollHideDelta(from: oldOffset, to: newOffset, maxOffset: maxOffset)
        scrolled(by: delta, timestamp: timestamp)
    }

    /// Tracks SwiftUI scroll phase transitions to settle the bar cleanly when interaction ends.
    func handleScrollPhaseChange(oldPhase: ScrollPhase, newPhase: ScrollPhase) {
        switch newPhase {
        case .interacting:
            isInteracting = true
            runner.stop()
            smoothedVelocity = 0
            lastScrollTime = CACurrentMediaTime()
        case .tracking:
            isInteracting = true
        case .decelerating:
            isInteracting = false
            // Keep feeding decelerating frames until idle if a strong fling happened,
            // or decide once idle if the bar is mid-transition.
        case .animating:
            isInteracting = false
        case .idle:
            let wasMoving = isInteracting || oldPhase == .decelerating || oldPhase == .tracking
            isInteracting = false
            if wasMoving {
                if let y = lastScrollOffset, y <= 0.5 {
                    show()
                } else {
                    released(velocity: smoothedVelocity)
                }
            }
            resetGesture()
        @unknown default:
            isInteracting = false
            resetGesture()
        }
    }

    /// Settles `fraction` after a scroll release given release velocity in pt/s (`> 0` toward hide).
    func released(velocity: CGFloat) {
        guard enabled else { return }
        guard fraction > 0, fraction < 1 else {
            resetGesture()
            return
        }
        let target: CGFloat
        if velocity > Self.flingDecisionVelocity {
            target = 1
        } else if velocity < -Self.flingDecisionVelocity {
            target = 0
        } else {
            target = fraction >= 0.5 ? 1 : 0
        }
        settle(to: target, velocity: velocity / Self.hideDistance)
    }

    /// Smoothly reveals the bar on its spatial spring (e.g., returning to Home or top-of-feed jump).
    func show() {
        resetGesture()
        settle(to: 0)
    }

    /// Immediately resets the bar to visible without motion (for transitions offscreen).
    func showUnseen() {
        runner.stop()
        resetGesture()
        if fraction != 0 { fraction = 0 }
    }

    private func resetGesture() {
        lastScrollTime = nil
        smoothedVelocity = 0
    }

    private func settle(to target: CGFloat, velocity: CGFloat = 0) {
        let clamped = min(max(target, 0), 1)
        if abs(fraction - clamped) < 0.0005 {
            runner.stop()
            if fraction != clamped { fraction = clamped }
            return
        }
        let current = fraction
        runner.run(
            from: current,
            to: clamped,
            velocity: velocity,
            spring: spring
        ) { [weak self] value in
            guard let self else { return }
            let next = min(max(value, 0), 1)
            if abs(next - self.fraction) > 0.0001 || (next == 0 && self.fraction != 0) || (next == 1 && self.fraction != 1) {
                self.fraction = next
            }
        }
    }
}
