import Foundation

/// The outro (Android `UpNextTiming.kt`), in playhead time:
/// - from `revealStartMs` the last line is held wide (I-2, focus only) and,
///   with a next song known, its title and first line slowly fade up (to
///   partial strength) and rise from below: one window, one progress;
/// - at `handoffAtMs` the stretch lets go, the list glides the next song's
///   first line up to where its own list opens, it comes to full strength and
///   this song's lines fade out, so the song change swaps in place.
struct UpNextTiming: Equatable {
    var revealStartMs: Int64
    var handoffAtMs: Int64

    /// The next song as the outro shows it: its title and first timed line.
    struct Next: Equatable {
        var key: String
        var title: String
        var firstLine: String
    }

    /// How far into the window [positionMs] is, 0…1, in `steps` steps (springs smooth them).
    func progress(_ positionMs: Int64, steps: Int = Self.revealSteps) -> CGFloat {
        let span = max(handoffAtMs - revealStartMs, 1)
        let p = min(max(Double(positionMs - revealStartMs) / Double(span), 0), 1)
        return CGFloat((p * Double(steps)).rounded() / Double(steps))
    }

    func handingOver(_ positionMs: Int64) -> Bool { positionMs >= handoffAtMs }

    /// Null without a next song or with untimed next lyrics (its own list
    /// opens untimed, so the swap could not match), else the last line's window.
    static func forUpNext(lastLineStartMs: Int64?, nextTimed: Bool, durationMs: Int64) -> UpNextTiming? {
        guard nextTimed else { return nil }
        return lastLineOutro(lastLineStartMs: lastLineStartMs, durationMs: durationMs)
    }

    /// The last line's outro window, with or without a next song: it opens as
    /// the last line takes the stage (or `revealLeadMs` before the end after a
    /// long tail) and hands over before the end. Null for untimed lyrics, an
    /// unknown duration, or a last line inside the final `handoffMinLeadMs`.
    static func lastLineOutro(lastLineStartMs: Int64?, durationMs: Int64) -> UpNextTiming? {
        guard durationMs > 0, let last = lastLineStartMs, durationMs - last >= handoffMinLeadMs else { return nil }
        let handoff = max(durationMs - handoffLeadMs, min(last + minLastLineMs, durationMs - handoffMinLeadMs))
        let reveal = max(durationMs - revealLeadMs, last)
        return UpNextTiming(revealStartMs: reveal, handoffAtMs: handoff)
    }

    static let revealSteps = 24
    /// How long before the end the next song starts to show.
    static let revealLeadMs: Int64 = 10_000
    /// How long before the end the next title glides up and takes over.
    static let handoffLeadMs: Int64 = 1_400
    /// Latest hand-over, measured from the end.
    static let handoffMinLeadMs: Int64 = 900
    /// The last line keeps the stage at least this long before the hand-over.
    static let minLastLineMs: Int64 = 2_000
    /// Peak widening of the held last line (Android LastLineStretchMax).
    static let lastLineStretchMax: CGFloat = 0.08
    /// The next song's reveal before the hand-over (Android UpNextRevealAlpha).
    static let revealAlpha: Double = 0.4
}
