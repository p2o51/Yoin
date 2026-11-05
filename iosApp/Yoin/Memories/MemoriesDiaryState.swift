import SwiftUI
import YoinShared

/// Memories' back and gesture numbers (Android BackMotionTokens' Memories
/// rows, MemoriesDiaryState's rubber band): the only place these live.
enum MemoriesBackTokens {
    /// q from the top bar: 56 pt of finger travel or a 450 pt/s fling up commits.
    static let barDismissCommit: CGFloat = 56
    static let barDismissFling: CGFloat = 450
    /// p's travel (card ⇄ diary) in pt, and the half-speed band at the top of the diary text.
    static let diaryMorphDistance: CGFloat = 320
    static let diaryPullBand: CGFloat = 24
    /// The card's own pull-down below p 0: 0.3×, floored at 90 pt of morph travel.
    static let diaryRubberBand: CGFloat = 0.3
    static let diaryRubberBandFloor: CGFloat = 90
    /// A release past p .5 on the diary's handle closes it (and its CLOCK_TICK).
    static let diaryCloseThreshold: CGFloat = 0.5
    /// The drag's axis locks after this much travel (Android's touch slop, the deck's DragGesture).
    static let touchSlop: CGFloat = 10
}

/// One dismiss rule: commit distance (pt of finger travel) and fling speed (pt/s).
struct MemoriesDismissRule: Equatable {
    var commit: CGFloat
    var fling: CGFloat

    static let bar = MemoriesDismissRule(commit: MemoriesBackTokens.barDismissCommit, fling: MemoriesBackTokens.barDismissFling)
    static let body = MemoriesDismissRule(commit: MemoriesPresentation.dismissCommit, fling: MemoriesPresentation.dismissFling)

    /// The page followed the finger only after [slopUp] pt of upward travel (the lock):
    /// the commit stays a finger distance from touch-down, so the page needs that much less.
    func afterSlop(_ slopUp: CGFloat) -> MemoriesDismissRule {
        MemoriesDismissRule(commit: max(commit - slopUp, 0), fling: fling)
    }
}

/// Memories' inner controller p (Android MemoriesDiaryState): the card ⇄
/// diary morph, an in-page state machine inside the ShellOverlayUp (the outer
/// q is MemoriesPresentation). Every gesture goes to exactly one of the two.
///
///   fraction 0 → card, 1 → diary, < 0 → the card's own pull-down rubber band.
///
/// The diary's scroll is not part of p: only a pull past the top of the text
/// reaches [pullBy]. One settle owner: any new input stops the running spring
/// first, and every move (open, close, release, back commit / cancel) rides
/// the same spring, the expressive default spatial one (Reduce Motion: the
/// slow effects spring, the morph alpha only).
@MainActor
@Observable
final class MemoriesDiary {
    private(set) var fraction: CGFloat = 0 { didSet { updateFlags() } }
    /// A spring is moving p. The award reads it (p has landed).
    private(set) var isSettling = false
    /// Where the running spring goes (0 card, 1 diary).
    private(set) var target: CGFloat = 0 { didSet { updateFlags() } }
    /// Back steps the diary (diary → card) from here up; below it back goes to q.
    /// Stored, so a frame of p invalidates only its readers (it flips once per motion).
    private(set) var isDiaryLevel = false
    /// Any of the diary is on screen (or about to be): mounted only then.
    private(set) var isShown = false
    /// Reduce Motion: the morph fades instead of flying (the same p).
    @ObservationIgnored var reduced = false

    @ObservationIgnored private let spring = SpringRunner()
    @ObservationIgnored private var travel: CGFloat = 0
    @ObservationIgnored private var banded = false
    @ObservationIgnored private var fromScrolled = false
    @ObservationIgnored private var ceiling: CGFloat = 1

    private var distance: CGFloat { MemoriesBackTokens.diaryMorphDistance }
    private var band: CGFloat { MemoriesBackTokens.diaryPullBand }
    private var rubber: CGFloat { MemoriesBackTokens.diaryRubberBand }
    private var minFraction: CGFloat { -MemoriesBackTokens.diaryRubberBandFloor / distance }

    private func updateFlags() {
        let level = fraction >= 0.5, shown = fraction > 0.001 || target > 0
        if level != isDiaryLevel { isDiaryLevel = level }
        if shown != isShown { isShown = shown }
    }

    /// Stops any spring; returns where p stopped.
    @discardableResult
    func stop() -> CGFloat {
        spring.stop()
        isSettling = false
        target = fraction > 0.5 ? 1 : 0
        return fraction
    }

    /// Without motion (a closed deck rebuilding).
    func snap(to value: CGFloat) {
        stop()
        fraction = min(max(value, minFraction), 1)
        target = fraction >= 0.5 ? 1 : 0
    }

    /// A finger takes p where it is (never jumped). [banded]: the pull came out
    /// of the diary text (its first 24 pt past the top at half speed);
    /// [fromScrolled]: that text was scrolled at finger-down; [ceiling] caps p
    /// for this pull (the card's own pull-down passes 0: it never opens the diary).
    func startPull(banded: Bool, fromScrolled: Bool, ceiling: CGFloat = 1) {
        stop()
        self.banded = banded
        self.fromScrolled = banded && fromScrolled
        self.ceiling = max(min(max(ceiling, 0), 1), min(fraction, 1))
        travel = Self.morphToPull(fraction * distance, distance: distance, band: band, banded: banded, rubber: rubber)
    }

    /// A finger delta (positive = finger down = toward the card). Returns what p took.
    @discardableResult
    func pullBy(_ delta: CGFloat) -> CGFloat {
        spring.stop()
        isSettling = false
        let maxTravel = Self.morphToPull(ceiling * distance, distance: distance, band: band, banded: banded, rubber: rubber)
        let minTravel = Self.morphToPull(minFraction * distance, distance: distance, band: band, banded: banded, rubber: rubber)
        let next = min(max(travel - delta, minTravel), maxTravel)
        let consumed = travel - next
        travel = next
        let morph = Self.pullToMorph(next, distance: distance, band: band, banded: banded, rubber: rubber)
        fraction = min(max(morph / distance, minFraction), 1)
        return consumed
    }

    /// Lets the pull go at [velocity] pt/s (positive = finger down). Returns the endpoint.
    @discardableResult
    func releasePull(velocity: CGFloat) -> CGFloat {
        let open = -velocity
        let to = Self.releaseTarget(fraction: fraction, openVelocity: open, distance: distance, band: band,
                                    flick: MemoriesPresentation.flickBack, fromScrolled: fromScrolled)
        let heldAtTop = fromScrolled && to >= 1 && fraction < 1 && fraction * distance >= distance - band / 2
        run(to: to, velocity: heldAtTop ? 0 : open / distance)
        return to
    }

    /// The Diary button, the bar cover / ⌄, back at the diary level.
    func animate(to value: CGFloat) {
        run(to: min(max(value, 0), 1), velocity: 0)
    }

    private func run(to: CGFloat, velocity: CGFloat) {
        target = to
        guard fraction != to || velocity != 0 else { isSettling = false; return }
        isSettling = true
        let spec: Spring = reduced ? .yoin(Tokens.motion.expressiveSlowEffects) : .yoin(Tokens.motion.expressiveDefaultSpatial)
        spring.run(from: fraction, to: to, velocity: velocity, spring: spec) { [weak self] v in
            self?.fraction = v
        } completion: { [weak self] in
            self?.fraction = to
            self?.isSettling = false
        }
    }

    // MARK: Pure rules (unit-tested, Android diaryPullToMorph / diaryMorphToPull / chooseDiaryReleaseTarget)

    /// Finger travel → morph travel (both pt toward the diary; 0 = card,
    /// [distance] = diary at the top of its text). Banded: the first [band]
    /// below the top at half speed, shifted past it so the map is continuous.
    /// Below 0 the card's rubber band.
    nonisolated static func pullToMorph(_ travel: CGFloat, distance: CGFloat, band: CGFloat, banded: Bool,
                                        rubber: CGFloat = MemoriesBackTokens.diaryRubberBand) -> CGFloat {
        let morph: CGFloat = if !banded || travel >= distance {
            travel
        } else if travel >= distance - band {
            distance - (distance - travel) * 0.5
        } else {
            travel + band / 2
        }
        return morph < 0 ? morph * rubber : morph
    }

    /// The inverse: where a pull resumes from the current p.
    nonisolated static func morphToPull(_ morphIn: CGFloat, distance: CGFloat, band: CGFloat, banded: Bool,
                                        rubber: CGFloat = MemoriesBackTokens.diaryRubberBand) -> CGFloat {
        let morph = morphIn < 0 && rubber > 0 ? morphIn / rubber : morphIn
        if !banded || morph >= distance { return morph }
        if morph >= distance - band / 2 { return distance - (distance - morph) * 2 }
        return morph - band / 2
    }

    /// Where a released pull settles. [openVelocity] is positive toward the diary.
    /// A pull that started in scrolled text and is still in the first half of
    /// the band stays in the diary however fast (a fling "to the top" is reading).
    nonisolated static func releaseTarget(fraction: CGFloat, openVelocity: CGFloat, distance: CGFloat, band: CGFloat,
                                          flick: CGFloat, fromScrolled: Bool) -> CGFloat {
        if fraction < 0 { return 0 }
        if fraction >= 1 || distance <= 0 { return 1 }
        if fromScrolled && fraction * distance >= distance - band / 2 { return 1 }
        if openVelocity > flick { return 1 }
        if openVelocity < -flick { return 0 }
        return fraction > 0.5 ? 1 : 0
    }
}
