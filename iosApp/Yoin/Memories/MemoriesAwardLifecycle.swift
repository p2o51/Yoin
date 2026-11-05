import Foundation
import SwiftUI

/*
 * The groove award's lifecycle in the deck (Android award/MemoriesAwardLifecycle):
 *
 *  · Each card gets its tiered award once per Memories open, the first time it
 *    is fully on show: on open once the reveal is 85 % in (q ≤ .15), when a
 *    page settle comes within .15 of it with the finger off the glass, and when
 *    the diary closes back onto it (p settled at 0). The first beat lands
 *    ≥ 120 ms after the finger lifts. A new open is a new lifecycle.
 *  · In the diary a card is never awarded: met there (a page swipe with the
 *    diary open) it only nods the diary title's 48 pt emblem; its full award
 *    waits for the card. Opening the diary on a card still due its award skips
 *    the nod: the 48 lands uncut, and the award cuts both when the diary closes.
 *  · A card not yet awarded waits in the award's first frame (nothing cut).
 *  · Any drag pauses the beats (the picture plays on); back on the same card resumes them.
 *  · Really leaving a card before its climax beat un-awards it: once the pager
 *    has settled elsewhere it waits again. Past the climax it counts.
 *  · Unrated cards have no award and never wait.
 */

/// Award timing (not back thresholds: they gate a ceremony, they move nothing).
enum MemoriesAwardTokens {
    static let firstBeatAfterLiftMs: Int64 = 120
    static let openRevealFraction: CGFloat = 0.15
    static let pagerSettleDistance: CGFloat = 0.15
    static let cardStateEpsilon: CGFloat = 0.002
    static let diaryStateFrom: CGFloat = 0.98
}

/// One card's award as the lifecycle drives it (ScoreEmblemAward in the app, a fake in tests).
@MainActor
protocol MemoriesAwardTarget: AnyObject {
    var hasAward: Bool { get }
    func showPending()
    func playFirst() -> MemoriesAwardRun
}

/// One card's diary emblem (the 48 pt groove by the diary title).
@MainActor
protocol MemoriesDiaryEmblemTarget: AnyObject {
    var hasAward: Bool { get }
    func showWaiting()
    func nod()
    func showRest()
}

@MainActor
protocol MemoriesAwardRun: AnyObject {
    /// The strongest beat's time has passed: an interruption no longer un-awards the card.
    var climaxed: Bool { get }
    func interrupt()
    func pauseBeats()
    func resumeBeats()
}

/// What decides whether an award is due, read together from the deck.
struct MemoriesAwardInputs: Equatable {
    var cardKey: String?
    var fingerDown: Bool
    var nearCard: Bool
    var revealIn: Bool
    var cardState: Bool
    var diaryState = false
    var blocked = false

    static func revealIn(_ q: CGFloat) -> Bool { q <= MemoriesAwardTokens.openRevealFraction }
    static func near(position: CGFloat, card: Int) -> Bool { abs(position - CGFloat(card)) < MemoriesAwardTokens.pagerSettleDistance }
    static func cardState(_ p: CGFloat) -> Bool { abs(p) <= MemoriesAwardTokens.cardStateEpsilon }
    static func diaryState(_ p: CGFloat, settling: Bool) -> Bool { p > MemoriesAwardTokens.diaryStateFrom && !settling }
}

/// The award bookkeeping of one Memories open. Only [fingerDown] is observed (the award gate reads it).
@MainActor
@Observable
final class MemoriesAwardLifecycle {
    private struct Running { var key: String; var run: MemoriesAwardRun; var first: Bool }

    @ObservationIgnored private var targets: [String: MemoriesAwardTarget] = [:]
    @ObservationIgnored private var diaryTargets: [String: MemoriesDiaryEmblemTarget] = [:]
    @ObservationIgnored private var awarded: Set<String> = []
    @ObservationIgnored private var nodded: Set<String> = []
    @ObservationIgnored private var unaward: Set<String> = []
    @ObservationIgnored private var running: Running?
    @ObservationIgnored private var liftMs: Int64 = .min / 2
    @ObservationIgnored private let clock: () -> Int64

    private(set) var fingerDown = false

    init(clock: @escaping () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000) }) {
        self.clock = clock
    }

    nonisolated static func startDelayMs(liftMs: Int64, nowMs: Int64) -> Int64 {
        max(0, liftMs + MemoriesAwardTokens.firstBeatAfterLiftMs - nowMs)
    }

    func isAwarded(_ key: String) -> Bool { awarded.contains(key) }
    func isNodded(_ key: String) -> Bool { nodded.contains(key) }

    func register(_ key: String, _ target: MemoriesAwardTarget) {
        targets[key] = target
        if target.hasAward && !awarded.contains(key) { target.showPending() }
    }

    func registerDiary(_ key: String, _ target: MemoriesDiaryEmblemTarget) {
        diaryTargets[key] = target
        syncDiary(key)
    }

    private func syncDiary(_ key: String) {
        guard let t = diaryTargets[key], t.hasAward else { return }
        if awarded.contains(key) { t.showRest() } else { t.showWaiting() }
    }

    /// The Diary button opened the diary on [key]: a running award stops and
    /// counts; a card still due skips its nod (the card award cuts the 48 later).
    func onDiaryOpening(_ key: String?) {
        if let r = running {
            r.run.interrupt()
            running = nil
        }
        guard let key, !awarded.contains(key) else { return }
        nodded.insert(key)
        if let t = diaryTargets[key], t.hasAward { t.showWaiting() }
    }

    func isNodDue(_ i: MemoriesAwardInputs) -> Bool {
        guard let key = i.cardKey, !i.fingerDown, i.nearCard, i.revealIn, !i.blocked, i.diaryState else { return false }
        if awarded.contains(key) || nodded.contains(key) { return false }
        return diaryTargets[key]?.hasAward == true
    }

    @discardableResult
    func nod(_ i: MemoriesAwardInputs) -> Bool {
        guard isNodDue(i), let key = i.cardKey else { return false }
        nodded.insert(key)
        diaryTargets[key]?.nod()
        return true
    }

    func onFingerDown() { fingerDown = true }

    func onFingerUp() {
        fingerDown = false
        liftMs = clock()
    }

    func onDragStart() { running?.run.pauseBeats() }

    /// The pager is on (or heading to) [key] with the finger off.
    func onCardTargeted(_ key: String?) {
        guard let r = running else { return }
        if r.key == key {
            r.run.resumeBeats()
            return
        }
        if r.first && !r.run.climaxed { unaward.insert(r.key) }
        r.run.interrupt()
        running = nil
    }

    /// The pager came to rest on [key]: cards cut off before their climax wait again, off screen.
    func onPagerSettled(_ key: String?) {
        guard !unaward.isEmpty else { return }
        for k in unaward where k != key {
            awarded.remove(k)
            targets[k]?.showPending()
            syncDiary(k)
        }
        unaward.removeAll()
    }

    /// The retreat to Home committed: the award stops (it counts).
    func onDismissCommitted() {
        running?.run.interrupt()
        running = nil
    }

    func isDue(_ i: MemoriesAwardInputs) -> Bool {
        guard let key = i.cardKey, !i.fingerDown, i.nearCard, i.revealIn, !i.blocked, i.cardState else { return false }
        if awarded.contains(key) { return false }
        return targets[key]?.hasAward == true
    }

    /// Nil when nothing is due; else how long to wait (the first beat ≥ 120 ms after the lift).
    func startDelayMs(_ i: MemoriesAwardInputs) -> Int64? {
        isDue(i) ? Self.startDelayMs(liftMs: liftMs, nowMs: clock()) : nil
    }

    @discardableResult
    func start(_ i: MemoriesAwardInputs) -> Bool {
        guard isDue(i), let key = i.cardKey, let target = targets[key] else { return false }
        running?.run.interrupt()
        awarded.insert(key)
        diaryTargets[key]?.showRest()
        running = Running(key: key, run: target.playFirst(), first: true)
        return true
    }

    /// One step of the deck's effect (Android MemoriesAwardEffects): the inputs changed.
    /// Returns the delay before [start] should be tried, or nil.
    func inputsChanged(_ i: MemoriesAwardInputs) -> Int64? {
        if !i.fingerDown { onCardTargeted(i.cardKey) }
        if nod(i) { return nil }
        return startDelayMs(i)
    }
}

// MARK: - ScoreEmblemAward as the lifecycle's targets

/// A card's 96 pt emblem award.
@MainActor
final class EmblemAwardTarget: MemoriesAwardTarget {
    let award: ScoreEmblemAward

    init(_ award: ScoreEmblemAward) { self.award = award }

    var hasAward: Bool { award.kind != .unrated }

    func showPending() { award.setPending(true) }

    func playFirst() -> MemoriesAwardRun {
        award.play(.first)
        return Run(award: award, climaxMs: award.climaxMs, started: Date())
    }

    private final class Run: MemoriesAwardRun {
        let award: ScoreEmblemAward
        let climaxMs: Int
        let started: Date

        init(award: ScoreEmblemAward, climaxMs: Int, started: Date) {
            self.award = award
            self.climaxMs = climaxMs
            self.started = started
        }

        var climaxed: Bool { Date().timeIntervalSince(started) * 1000 >= Double(climaxMs) }
        func interrupt() { award.interrupt() }
        func pauseBeats() { award.pauseBeats() }
        func resumeBeats() { award.resumeBeats() }
    }
}

/// The diary title's 48 pt emblem: uncut while its card waits, a nod when met in the diary.
@MainActor
@Observable
final class DiaryEmblemTarget: MemoriesDiaryEmblemTarget {
    let award: ScoreEmblemAward
    /// The nod's pulse (bumped per nod; the emblem springs a scale on it).
    private(set) var nods = 0

    init(_ award: ScoreEmblemAward) { self.award = award }

    var hasAward: Bool { award.kind != .unrated }
    func showWaiting() { award.setPending(true) }
    func showRest() { award.reset() }

    func nod() {
        nods += 1
        MemoriesHaptics.soft(0.5)
    }
}
