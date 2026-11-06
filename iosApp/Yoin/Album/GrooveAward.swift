import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/*
 * 唱片刻纹 · the score emblem's award ("cutting") ceremony. Port of Android
 * ui/memories/emblem/GrooveScript.kt (tiers, channels, beat plan),
 * GrooveAward.kt (pending / play / replay / interrupt-settle / reduced
 * motion) and GrooveHaptics.kt (`awardBeats`).
 *
 * Every channel is a closed-form spring of t (dampingRatio z, stiffness k,
 * mass 1), so nothing here is a tween: the emblem samples the channels at
 * each TimelineView frame. Haptic beats are found on the same curves that
 * draw the picture (a settle frame, an overshoot peak, a label contact, a
 * ring at its brightest) — change a spring and the beats move with it.
 *
 * Tiers, on the displayed one-decimal score: 1 below 6 · 2 6–7.9 ·
 * 3 8–9.9 · 4 exactly 10.0.
 */

// MARK: - Math

enum GrooveMath {
    /// Where a spring (z, k, mass 1) from x0 to x1 with initial velocity v0 is at t seconds.
    static func springAt(_ z: Double, _ k: Double, _ t: Double, _ x0: Double, _ x1: Double, _ v0: Double = 0) -> Double {
        if t <= 0 { return x0 }
        let w0 = k.squareRoot()
        let d0 = x0 - x1
        let d: Double
        if z < 1 {
            let wd = w0 * (1 - z * z).squareRoot()
            d = exp(-z * w0 * t) * (d0 * cos(wd * t) + ((v0 + z * w0 * d0) / wd) * sin(wd * t))
        } else {
            d = exp(-w0 * t) * (d0 + (v0 + w0 * d0) * t)
        }
        return x1 + d
    }

    static func cl(_ v: Double) -> Double { min(1, max(0, v)) }

    static func gauss(_ t: Double, _ c: Double, _ w: Double) -> Double {
        let x = (t - c) / w
        return exp(-(x * x))
    }

    /// An effects-spring flash: a stiff critically damped rise, then an exponential fade.
    static func pulse(_ t: Double, _ at: Double, _ hold: Double) -> Double {
        t < at ? 0 : cl(springAt(1, 2400, t - at, 0, 1)) * exp(-max(0, t - at - 0.04) / hold)
    }

    /// The prototype's 1 ms scans.
    static func argmax(_ a: Double, _ b: Double, _ fn: (Double) -> Double) -> Double {
        var best = -Double.infinity, bt = a, t = a
        while t <= b {
            let v = fn(t)
            if v > best { best = v; bt = t }
            t += 0.001
        }
        return bt
    }

    static func argmin(_ a: Double, _ b: Double, _ fn: (Double) -> Double) -> Double { argmax(a, b) { -fn($0) } }

    static func firstT(_ a: Double, _ b: Double, _ pred: (Double) -> Bool) -> Double? {
        var t = a
        while t <= b {
            if pred(t) { return t }
            t += 0.001
        }
        return nil
    }

    static func jsRound(_ x: Double) -> Double { (x + 0.5).rounded(.down) }
    static func fixed2(_ x: Double) -> Double { (x * 100).rounded() / 100 }
}

/// Spring constants of the award flow (Android `GrooveSprings`).
enum GrooveSprings {
    /// Interrupted / unfinished award back to rest.
    static let settle = (z: 0.9, k: 520.0)
    /// Reduced-motion settle: alpha only, no overshoot.
    static let effects = (z: 1.0, k: 200.0)
    /// The reduced award's alpha reveal (97.7 % at 200 ms).
    static let reveal = (z: 1.0, k: 800.0)
    /// A replay's label joining the script from rest (~70 ms).
    static let replayJoin = (z: 1.0, k: 1600.0)
    /// The reduced replay's label dip.
    static let dip = (z: 1.0, k: 2400.0)
}

// MARK: - Haptic beats

/// Android `VibrationEffect.Composition` primitives, mapped to UIKit impacts in [GrooveHaptics].
enum GroovePrimitive { case tick, lowTick, click, thud, spin, quickRise }

struct GrooveBeat: Equatable {
    var atMs: Int
    var primitive: GroovePrimitive
    /// The primitive's 0–1 strength.
    var scale: Double
    var what: String
}

/// UIKit stand-ins for the Android primitives (the tablet has no motor; the
/// mapping was chosen against an iPhone's Taptic Engine):
/// TICK → rigid, LOW_TICK → soft, CLICK → medium, THUD → heavy,
/// SPIN → soft (a flick, not a click), QUICK_RISE → light.
#if os(macOS)
/// The Mac has no Taptic beats for the award: it plays silent.
@MainActor
enum GrooveHaptics {
    static func prepare(_ beats: [GrooveBeat]) {}
    static func fire(_ beat: GrooveBeat) {}
}
#else
@MainActor
enum GrooveHaptics {
    private static var generators: [UIImpactFeedbackGenerator.FeedbackStyle: UIImpactFeedbackGenerator] = [:]

    static func style(_ p: GroovePrimitive) -> UIImpactFeedbackGenerator.FeedbackStyle {
        switch p {
        case .tick: .rigid
        case .lowTick: .soft
        case .click: .medium
        case .thud: .heavy
        case .spin: .soft
        case .quickRise: .light
        }
    }

    static func prepare(_ beats: [GrooveBeat]) {
        for b in beats { generator(style(b.primitive)).prepare() }
    }

    static func fire(_ beat: GrooveBeat) {
        generator(style(beat.primitive)).impactOccurred(intensity: CGFloat(beat.scale))
    }

    private static func generator(_ s: UIImpactFeedbackGenerator.FeedbackStyle) -> UIImpactFeedbackGenerator {
        if let g = generators[s] { return g }
        let g = UIImpactFeedbackGenerator(style: s)
        generators[s] = g
        return g
    }
}
#endif

// MARK: - Script

/// One tier's choreography (Android `GrooveScript`). [order] is a cut's ring
/// order among the rated rings (outer first); [ring] a ring index.
final class GrooveScript: Sendable {
    let tier: Int
    let album: Bool
    let ringCount: Int
    let ratedRingCount: Int
    let durationS: Double
    private let a: Double
    private let t2Cross: Double
    private let t2Peak: Double
    private let labAt: Double
    /// Tier 4: the moment the flat accent reaches the rim.
    private static let tf = 1.02
    /// Beats closer than this blur into one buzz; the stronger one is kept.
    static let mergeWindowMs = 45

    init(tier: Int, album: Bool, ringCount: Int, ratedRingCount: Int) {
        self.tier = tier
        self.album = album
        self.ringCount = ringCount
        self.ratedRingCount = ratedRingCount
        a = album ? 1 : 0.7
        durationS = switch tier { case 1: 0.62; case 2: 1.0; case 3: 1.3; default: 1.6 }
        labAt = tier == 4 ? 0.74 : 0.5
        if tier == 2 {
            let disc = { (t: Double) in GrooveMath.springAt(0.74, 70, t, -330, 0) }
            let cross = GrooveMath.firstT(0, 1.0) { disc($0) >= 0 }.flatMap { $0 == 0 ? nil : $0 } ?? 0.43
            t2Cross = cross
            t2Peak = GrooveMath.argmax(cross, 1.0, disc)
        } else {
            t2Cross = 0
            t2Peak = 0
        }
    }

    /// Disc (and album label) rotation, degrees; 0 at rest.
    func disc(_ t: Double) -> Double {
        switch tier {
        case 1: GrooveMath.springAt(1, 90, t, -16, 0)
        case 2: GrooveMath.springAt(0.74, 70, t, -330, 0)
        case 3: GrooveMath.springAt(0.8, 42, t, -360, 0, 1300)
        default: GrooveMath.springAt(0.82, 44, t, -720, 0, 2000)
        }
    }

    private func lift(_ t: Double) -> Double {
        tier == 4 ? GrooveMath.springAt(1, 200, t, 1, 1 + 0.18 * a) : GrooveMath.springAt(1, 220, t, 1, 1 + 0.12 * a)
    }

    /// Label scale; 1 at rest.
    func label(_ t: Double) -> Double {
        switch tier {
        case 1: return GrooveMath.springAt(0.9, 300, t, 1 + 0.06 * a, 1)
        case 2: return t < t2Peak ? 1 : GrooveMath.springAt(0.5, 420, t - t2Peak, 1, 1, -1.1 * a)
        case 3: return t < labAt ? lift(t) : GrooveMath.springAt(0.55, 520, t - labAt, lift(labAt), 1)
        default: return t < labAt ? lift(t) : GrooveMath.springAt(0.5, 600, t - labAt, lift(labAt), 1)
        }
    }

    /// How much of a cut is drawn, 0–1 (clockwise from its start).
    func cut(_ order: Int, _ t: Double) -> Double {
        let o = Double(order)
        return switch tier {
        case 1: GrooveMath.cl(GrooveMath.springAt(1, 140, t, 0, 1))
        case 2: GrooveMath.cl(GrooveMath.springAt(1, 150, t - (0.05 + o * 0.05), 0, 1))
        case 3: GrooveMath.cl(GrooveMath.springAt(1, 150, t - (0.04 + o * 0.06), 0, 1))
        default: GrooveMath.cl(GrooveMath.springAt(1, 150, t - (0.04 + o * 0.045), 0, 1))
        }
    }

    private func t3At(_ order: Int) -> Double { 0.72 + Double(order) * 0.08 }
    private func t4At(_ ring: Int) -> Double { 0.28 + Double(ring) * 0.055 }

    /// Opacity of a cut's flat "lit" colour.
    func cutGlow(_ order: Int, _ t: Double) -> Double {
        switch tier {
        case 2: 0.85 * GrooveMath.gauss(t, t2Peak + 0.01, 0.085)
        case 3: 0.95 * GrooveMath.pulse(t, t3At(order), 0.2)
        case 4: 0.7 * GrooveMath.pulse(t, Self.tf, 0.26)
        default: 0
        }
    }

    /// A whole ring lit in turn, outer → inner (tier 4 only).
    func ringGlow(_ ring: Int, _ t: Double) -> Double { tier == 4 ? 0.9 * GrooveMath.pulse(t, t4At(ring), 0.2) : 0 }

    /// The 10.0 rim pulse (tier 4 only).
    func flare(_ t: Double) -> Double { tier == 4 ? GrooveMath.pulse(t, Self.tf - 0.04, 0.3) : 0 }

    /// The thin ring released from the rim (tier 4 only).
    func burstScale(_ t: Double) -> Double {
        tier == 4 ? 1 + 0.2 * GrooveMath.cl(GrooveMath.springAt(1, 90, t - (Self.tf - 0.04), 0, 1)) : 1
    }

    func burstAlpha(_ t: Double) -> Double { tier == 4 ? 0.9 * GrooveMath.pulse(t, Self.tf - 0.04, 0.2) : 0 }

    /// The raw beat plan (prototype `hapticPlan`), merged so no two beats are closer than 45 ms.
    var beats: [GrooveBeat] {
        var out: [GrooveBeat] = []
        func beat(_ at: Double, _ p: GroovePrimitive, _ scale: Double, _ what: String) {
            out.append(GrooveBeat(atMs: Int(max(0, GrooveMath.jsRound(at * 1000))), primitive: p,
                                  scale: GrooveMath.fixed2(scale), what: what))
        }
        switch tier {
        case 1:
            let settle = GrooveMath.firstT(0.05, durationS) { label($0) <= 1 }.flatMap { $0 == 0 ? nil : $0 } ?? 0.36
            beat(settle, .lowTick, 0.5, "label settles")
        case 2:
            let fastest = GrooveMath.argmax(0, t2Cross) { disc($0 + 0.001) - disc($0) }
            beat(fastest, .tick, 0.5, "spin-up at its fastest")
            beat(t2Peak, .click, 0.75, "overshoot peak: cuts lit, label nods")
        case 3:
            let contact = GrooveMath.argmin(labAt, labAt + 0.3) { label($0) }
            beat(0, .spin, 0.5, "flick: one full turn")
            beat(contact, .click, 1.0, "label at its lowest")
            let n = ratedRingCount
            let stride = max(1, Int((Double(n) / 4).rounded(.up)))
            for j in Swift.stride(from: 0, to: n, by: stride) {
                let at = t3At(j)
                beat(GrooveMath.argmax(at, at + 0.2) { GrooveMath.pulse($0, at, 0.2) }, .lowTick, 0.35,
                     "rated ring \(j + 1) lit")
            }
        default:
            let contact = GrooveMath.argmin(labAt, labAt + 0.3) { label($0) }
            let flarePeak = GrooveMath.argmax(Self.tf - 0.04, Self.tf + 0.2) { flare($0) }
            beat(0, .spin, 0.8, "flick: two turns")
            let n = ringCount
            let stride = max(1, Int((Double(n) / 8).rounded(.up)))
            for i in Swift.stride(from: 0, to: n, by: stride) {
                let at = t4At(i)
                beat(GrooveMath.argmax(at, at + 0.2) { GrooveMath.pulse($0, at, 0.2) }, .tick, 0.45, "ring \(i + 1) lit")
            }
            beat(contact - 0.01, .thud, 1.0, "label stamps down")
            beat(Self.tf - 0.04, .quickRise, 0.7, "rim turns accent")
            beat(flarePeak, .click, 1.0, "rim fullest, ring released")
        }
        return Self.merge(out)
    }

    static func merge(_ beats: [GrooveBeat]) -> [GrooveBeat] {
        var kept: [GrooveBeat] = []
        for b in beats.sorted(by: { $0.atMs < $1.atMs }) {
            if let last = kept.last, b.atMs - last.atMs < mergeWindowMs {
                if b.scale > last.scale { kept[kept.count - 1] = b }
            } else {
                kept.append(b)
            }
        }
        return kept
    }

    /// Prototype `tierOf`: on the displayed one-decimal score.
    static func tier(score: Double) -> Int {
        let tenths = Int((score * 10 + 0.5).rounded(.down))
        return tenths >= 100 ? 4 : tenths >= 80 ? 3 : tenths >= 60 ? 2 : 1
    }
}

enum GrooveBeatMode { case first, replay }

/// The beats of one award (Android `awardBeats`): tier 4 keeps at most four
/// ring ticks ≥ 90 ms apart and drops QUICK_RISE; a replay keeps only the
/// climax; reduced motion keeps only the strongest beat, on the reveal's end.
func grooveAwardBeats(_ s: GrooveScript, tiny: Bool, mode: GrooveBeatMode, reduced: Bool) -> [GrooveBeat] {
    var plan = s.beats
    if s.tier == 4 {
        let n = tiny ? 0 : s.ringCount
        let stride = max(1, Int((Double(n) / 4).rounded(.up)))
        var keep = plan.filter { $0.primitive != .tick && $0.primitive != .quickRise }
        var last = -1_000_000_000
        for i in Swift.stride(from: 0, to: n, by: stride) {
            let at = GrooveMath.argmax(0, s.durationS) { s.ringGlow(i, $0) }
            let ms = Int(GrooveMath.jsRound(at * 1000))
            if ms - last >= 90 {
                keep.append(GrooveBeat(atMs: ms, primitive: .tick, scale: 0.35, what: "ring \(i + 1) lit"))
                last = ms
            }
        }
        plan = keep.sorted { $0.atMs < $1.atMs }
    }
    guard !plan.isEmpty else { return [] }
    if reduced {
        var strongest = plan.reduce(plan[0]) { a, b in
            b.scale > a.scale || (b.scale == a.scale && b.primitive == .thud) ? b : a
        }
        strongest.atMs = 200
        strongest.what = "reveal finished"
        return [strongest]
    }
    if mode == .replay {
        let keep: Set<GroovePrimitive> = switch s.tier {
        case 4: [.thud, .click]
        case 1: [.lowTick]
        default: [.click]
        }
        plan = plan.filter { keep.contains($0.primitive) }
    }
    return plan
}

// MARK: - Channels

/// What the emblem's drawing reads from an award at one instant.
struct GrooveChannels: Equatable {
    /// Disc rotation (the album label turns with it), degrees.
    var disc = 0.0
    var labelScale = 1.0
    var labelAlpha = 1.0
    /// 0 while waiting uncut: no tilt colour on grooves not cut yet.
    var tintAlpha = 1.0
    var flare = 0.0
    var burstAlpha = 0.0
    var burstScale = 1.0
    var cutProgress: [Double]
    var cutAlpha: [Double]
    var cutGlow: [Double]
    var ringGlow: [Double]

    static func rest(orders: Int, rings: Int) -> GrooveChannels {
        GrooveChannels(cutProgress: Array(repeating: 1, count: orders), cutAlpha: Array(repeating: 1, count: orders),
                       cutGlow: Array(repeating: 0, count: orders), ringGlow: Array(repeating: 0, count: rings))
    }

    func cut(_ order: Int) -> Double { cutProgress.indices.contains(order) ? cutProgress[order] : 1 }
    func alpha(_ order: Int) -> Double { cutAlpha.indices.contains(order) ? cutAlpha[order] : 1 }
    func glow(_ order: Int) -> Double { cutGlow.indices.contains(order) ? cutGlow[order] : 0 }
    func ring(_ ring: Int) -> Double { ringGlow.indices.contains(ring) ? ringGlow[ring] : 0 }

    /// rest + (self − rest) × e, with the disc taking the short way round.
    func toward(_ rest: GrooveChannels, by e: Double) -> GrooveChannels {
        func l(_ a: Double, _ r: Double) -> Double { r + (a - r) * e }
        func la(_ a: [Double], _ r: [Double]) -> [Double] { zip(a, r).map { l($0, $1) } }
        let d = ((disc.truncatingRemainder(dividingBy: 360)) + 540).truncatingRemainder(dividingBy: 360) - 180
        return GrooveChannels(disc: l(d, rest.disc), labelScale: l(labelScale, rest.labelScale),
                              labelAlpha: l(labelAlpha, rest.labelAlpha), tintAlpha: l(tintAlpha, rest.tintAlpha),
                              flare: l(flare, rest.flare), burstAlpha: l(burstAlpha, rest.burstAlpha),
                              burstScale: l(burstScale, rest.burstScale),
                              cutProgress: la(cutProgress, rest.cutProgress), cutAlpha: la(cutAlpha, rest.cutAlpha),
                              cutGlow: la(cutGlow, rest.cutGlow), ringGlow: la(ringGlow, rest.ringGlow))
    }

    func isNear(_ rest: GrooveChannels) -> Bool {
        let d = ((disc.truncatingRemainder(dividingBy: 360)) + 540).truncatingRemainder(dividingBy: 360) - 180
        guard abs(d - rest.disc) < 0.2 else { return false }
        let a = [labelScale, labelAlpha, tintAlpha, flare, burstAlpha, burstScale] + cutProgress + cutAlpha + cutGlow + ringGlow
        let b = [rest.labelScale, rest.labelAlpha, rest.tintAlpha, rest.flare, rest.burstAlpha, rest.burstScale]
            + rest.cutProgress + rest.cutAlpha + rest.cutGlow + rest.ringGlow
        return zip(a, b).allSatisfy { abs($0 - $1) < 0.003 }
    }
}

// MARK: - Controller

/// The award ceremony of one [ScoreEmblem]: the tiered "cutting" (tier by
/// the displayed score), its haptic beats, interrupt-settle and Reduce
/// Motion. Build it from the SAME score, kind, rated flags and size you
/// pass to the emblem, then hand it over as `award:` (a mismatch draws the
/// resting emblem). Port of Android `rememberScoreEmblemAwardState`.
///
/// The album scrapbook's stamp (Android AlbumScrapbookPage `CoverWithEmblem`,
/// D3 §8): once per album page, the award plays when the pager comes to rest
/// on the scrapbook; until then it waits uncut. The album page plays it
/// WITHOUT haptics (D3 §11.12); the rate sheet's replay has them.
///
/// ```swift
/// @State private var award = ScoreEmblemAward(score: s, kind: k, rated: r, size: 72, haptics: false)
/// @State private var stamped = false
/// ScoreEmblem(score: s, kind: k, rated: r, palette: p, size: 72, award: award)
///     .onAppear { if !stamped { award.setPending(true) } }
///     .onChange(of: pagerSettledOnScrapbook) { _, settled in
///         guard settled, !stamped else { return }
///         award.play(); stamped = true
///     }
/// ```
///
/// `play(.replay)` replays an emblem that is already cut (climax only — the
/// rate sheet's stamp when the finger lifts); `interrupt()` settles a running
/// award to rest from where it is (the page is leaving); `reset()` jumps to
/// the finished emblem. A controller left pending must be played or reset,
/// or its grooves stay uncut. With Reduce Motion the award develops by alpha
/// in ~200 ms with a single beat (the emblem syncs `reducedMotion` itself).
@MainActor
@Observable
final class ScoreEmblemAward {
    enum Mode { case first, replay }

    let score: Double
    let kind: ScoreEmblem.Kind
    let rated: [Bool]
    let size: CGFloat
    let haptics: Bool
    /// Synced from the environment's Reduce Motion by the emblem it drives.
    var reducedMotion = false

    /// True while a run or settle is moving the picture.
    private(set) var isAnimating = false
    /// The award's tier (1–4).
    var tier: Int { script.tier }

    let script: GrooveScript
    let layout: GrooveLayout
    private let rest: GrooveChannels
    private var pending = false
    private var run: Run?
    private var finisher: Task<Void, Never>?
    private var beatTask: Task<Void, Never>?

    private enum Run {
        case script(mode: Mode, reduced: Bool, start: Date, end: Double)
        case settle(from: GrooveChannels, reduced: Bool, start: Date, end: Double)
    }

    init(score: Double, kind: ScoreEmblem.Kind, rated: [Bool], size: CGFloat, haptics: Bool = true) {
        self.score = score
        self.kind = kind
        self.rated = rated
        self.size = size
        self.haptics = haptics
        layout = GrooveLayout(rated: rated, size: size)
        script = GrooveScript(tier: GrooveScript.tier(score: score), album: kind == .album,
                              ringCount: layout.rings, ratedRingCount: layout.ratedRings.count)
        rest = .rest(orders: max(1, layout.ratedRings.count), rings: layout.rings)
    }

    func drives(score: Double, kind: ScoreEmblem.Kind, rated: [Bool], size: CGFloat) -> Bool {
        self.score == score && self.kind == kind && self.rated == rated && self.size == size
    }

    // MARK: poses

    /// The waiting pose: the choreography's frame 0 (nothing cut; Reduce
    /// Motion: grooves and label undeveloped). `false` returns to rest.
    func setPending(_ on: Bool) {
        stopAll()
        pending = on
    }

    /// Stops everything and shows the finished emblem at once.
    func reset() { setPending(false) }

    // MARK: the award

    /// Plays the award (tier-scaled) from frame 0; a run already playing is replaced.
    func play(_ mode: Mode = .first) {
        stopAll()
        pending = false
        let reduced = reducedMotion
        let end = reduced ? 0.34 : script.durationS
        let start = Date()
        run = .script(mode: mode, reduced: reduced, start: start, end: end)
        isAnimating = true
        scheduleFinish(after: end)
        if haptics {
            let beats = grooveAwardBeats(script, tiny: layout.tiny, mode: mode == .first ? .first : .replay,
                                         reduced: reduced)
            GrooveHaptics.prepare(beats)
            beatPlan = (beats, start)
            scheduleBeats(after: 0)
        }
    }

    /// The first award's strongest beat (Android `strongestBeat`), in ms from its start:
    /// Memories counts a card as awarded once a run has passed it.
    var climaxMs: Int {
        grooveAwardBeats(script, tiny: layout.tiny, mode: .first, reduced: reducedMotion)
            .max { $0.scale < $1.scale }?.atMs ?? 0
    }

    /// Memories: a drag holds the beats while the picture plays on…
    func pauseBeats() {
        beatTask?.cancel()
        beatTask = nil
    }

    /// …and back on the same card the beats still ahead go on.
    func resumeBeats() {
        guard beatTask == nil, let plan = beatPlan, case .script? = run else { return }
        scheduleBeats(after: Int(Date().timeIntervalSince(plan.start) * 1000))
    }

    @ObservationIgnored private var beatPlan: (beats: [GrooveBeat], start: Date)?

    private func scheduleBeats(after elapsedMs: Int) {
        guard let plan = beatPlan else { return }
        let ahead = plan.beats.filter { $0.atMs >= elapsedMs }
        beatTask = Task { @MainActor in
            for b in ahead {
                let wait = plan.start.addingTimeInterval(Double(b.atMs) / 1000).timeIntervalSinceNow
                if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
                if Task.isCancelled { return }
                GrooveHaptics.fire(b)
            }
        }
    }

    /// Stops the beats at once and lets the picture settle to rest from where it is.
    func interrupt() {
        guard run != nil else { return }
        let now = Date()
        let snap = channels(at: now)
        beatTask?.cancel()
        beatSettle(from: snap, at: now)
    }

    private func beatSettle(from snap: GrooveChannels, at now: Date) {
        finisher?.cancel()
        let reduced = reducedMotion
        let spec = reduced ? GrooveSprings.effects : GrooveSprings.settle
        let end = GrooveMath.firstT(0.05, 2) { abs(GrooveMath.springAt(spec.z, spec.k, $0, 1, 0)) < 0.002 } ?? 0.6
        run = .settle(from: snap, reduced: reduced, start: now, end: end)
        isAnimating = true
        scheduleFinish(after: end)
    }

    private func scheduleFinish(after seconds: Double) {
        finisher?.cancel()
        finisher = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, let self else { return }
            self.finish()
        }
    }

    private func finish() {
        guard let run else { return }
        switch run {
        case .script(let mode, let reduced, _, let end):
            let last = sample(mode: mode, reduced: reduced, t: end)
            if last.isNear(rest) {
                stopAll()
            } else {
                beatSettle(from: last, at: Date())
            }
        case .settle:
            finisher = nil
            self.run = nil
            isAnimating = false
        }
    }

    private func stopAll() {
        beatTask?.cancel()
        beatTask = nil
        beatPlan = nil
        finisher?.cancel()
        finisher = nil
        run = nil
        isAnimating = false
    }

    // MARK: sampling

    /// The picture at [date]: the running award, else the still pose.
    func channels(at date: Date) -> GrooveChannels {
        switch run {
        case .script(let mode, let reduced, let start, let end)?:
            return sample(mode: mode, reduced: reduced, t: min(end, max(0, date.timeIntervalSince(start))))
        case .settle(let from, let reduced, let start, let end)?:
            let t = min(end, max(0, date.timeIntervalSince(start)))
            let spec = reduced ? GrooveSprings.effects : GrooveSprings.settle
            return t >= end ? rest : from.toward(rest, by: GrooveMath.springAt(spec.z, spec.k, t, 1, 0))
        case nil:
            return pending ? pendingPose : rest
        }
    }

    private var pendingPose: GrooveChannels {
        var c = rest
        c.tintAlpha = 0
        if reducedMotion {
            c.cutAlpha = c.cutAlpha.map { _ in 0 }
            c.labelAlpha = 0
        } else {
            c = sample(mode: .first, reduced: false, t: 0)
            c.tintAlpha = 0
        }
        return c
    }

    /// Android `channels(s, mode)` at t (twostate4 `awardChans`).
    private func sample(mode: Mode, reduced: Bool, t: Double) -> GrooveChannels {
        var c = rest
        let s = script
        let orders = c.cutProgress.count
        if reduced {
            if mode == .first {
                let develop = GrooveMath.cl(GrooveMath.springAt(GrooveSprings.reveal.z, GrooveSprings.reveal.k, t, 0, 1))
                c.cutAlpha = Array(repeating: develop, count: orders)
                c.labelAlpha = develop
            } else {
                let dip = GrooveMath.cl(GrooveMath.springAt(GrooveSprings.dip.z, GrooveSprings.dip.k, t, 0, 1))
                c.labelAlpha = 1 - 0.45 * dip * exp(-max(0, t - 0.04) / 0.12)
            }
            return c
        }
        let replay = mode == .replay
        // a replay starts from the resting picture: tier 2's −330° becomes −360°, tier 1's −16° becomes 0°
        let d0 = s.disc(0)
        let discScale = !replay ? 1 : d0 != 0 ? GrooveMath.jsRound(d0 / 360) * 360 / d0 : 0
        c.disc = s.disc(t) * discScale
        c.labelScale = replay
            ? 1 + (s.label(t) - 1) * GrooveMath.cl(GrooveMath.springAt(GrooveSprings.replayJoin.z,
                                                                        GrooveSprings.replayJoin.k, t, 0, 1))
            : s.label(t)
        for j in 0..<orders {
            if !replay { c.cutProgress[j] = s.cut(j, t) }
            c.cutGlow[j] = s.cutGlow(j, t)
        }
        if s.tier == 4 {
            for i in 0..<c.ringGlow.count { c.ringGlow[i] = s.ringGlow(i, t) }
            c.flare = s.flare(t)
            c.burstAlpha = s.burstAlpha(t)
            c.burstScale = s.burstScale(t)
        }
        return c
    }
}

// MARK: - Layout

/// The emblem's ring layout (shared by the drawing and the award): ring
/// count by size, each ring a contiguous chunk of tracks (outer = track 1),
/// rated tracks merged into runs (degrees from 12 o'clock, clockwise).
struct GrooveLayout: Equatable {
    struct Run: Equatable { var start: Double; var sweep: Double }
    /// Track index range per ring.
    let chunks: [Range<Int>]
    /// Rated runs per ring.
    let runs: [[Run]]
    /// Ring indices that hold a cut, outer first.
    let ratedRings: [Int]
    let rings: Int
    /// Below 60 pt: no lit layers, no ring ticks (Android `GrooveSizes.TinyBelow`).
    let tiny: Bool

    init(rated: [Bool], size: CGFloat) {
        let rings = size < 64 ? 3 : size < 88 ? 6 : size < 110 ? 8 : 12
        let n = max(rated.count, 1)
        var chunks: [Range<Int>] = []
        var runs: [[Run]] = []
        for ring in 0..<rings {
            let from = Int((Double(ring) * Double(n) / Double(rings)).rounded())
            let to = Int((Double(ring + 1) * Double(n) / Double(rings)).rounded())
            chunks.append(from..<max(from, to))
            var ringRuns: [Run] = []
            if to > from {
                let share = 360.0 / Double(to - from)
                for t in from..<to where rated.indices.contains(t) && rated[t] {
                    let a0 = -90 + share * Double(t - from)
                    if let last = ringRuns.last, abs(last.start + last.sweep - a0) < 0.001 {
                        ringRuns[ringRuns.count - 1].sweep += share
                    } else {
                        ringRuns.append(Run(start: a0, sweep: share))
                    }
                }
            }
            runs.append(ringRuns)
        }
        self.chunks = chunks
        self.runs = runs
        self.rings = rings
        ratedRings = runs.indices.filter { !runs[$0].isEmpty }
        tiny = size < 60
    }

    func order(of ring: Int) -> Int { max(0, ratedRings.firstIndex(of: ring) ?? 0) }
}
