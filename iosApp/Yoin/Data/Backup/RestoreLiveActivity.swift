// Activity isn't Sendable in the SDK: handing it to its own async end/update reads as a data race in Swift 6.
@preconcurrency import ActivityKit
import Foundation
import UIKit
import YoinShared

/// Where a restore's progress shows outside the app (Android RestoreProgressSink): the Live Activity.
@MainActor
protocol RestoreProgressSink: AnyObject {
    /// [outcomes]: what each song resolved to so far, in the order they came back.
    func matching(playlist: String, target: RestoreTarget, done: Int, total: Int, outcomes: [ReviewStatus])
    func writing(playlist: String, target: RestoreTarget, count: Int)
    func finished(playlist: String, target: RestoreTarget, record: PlaylistRestoreRecord)
    /// Matching is over (the review waits in the app), it failed, or the restore was dropped.
    func clear()
}

/// One restore's Live Activity (Android RestoreLiveUpdate; Widgets/RestoreActivity.swift): started when
/// matching starts and when the write starts, ended when matching is over and when the write is. Both
/// starts come from a tap in Yoin, the only time ActivityKit lets one begin; updates and ends also come
/// with Yoin left. While it shows, the restore keeps going for the time iOS gives once Yoin is left (all
/// along while music plays). Nothing shows with Live Activities off for Yoin.
@MainActor
final class RestoreLiveActivity: RestoreProgressSink {
    /// The fastest the activity follows matching (songs come back far faster).
    static let interval: Duration = .milliseconds(500)
    /// Android ONGOING_TIMEOUT_MS: an activity no longer updated (Yoin stopped mid-restore) reads as stale.
    static let staleAfter: TimeInterval = 120

    /// The activities this launch is showing; any other one still under way was left by an earlier launch.
    private static var shown: Set<String> = []

    private var activity: Activity<RestoreActivityAttributes>?
    /// The stage that couldn't have an activity: not asked again until it's over.
    private var refused: RestoreActivityAttributes?
    private var pending: RestoreActivityAttributes.ContentState?
    private var lastPush: ContinuousClock.Instant?
    private var trailing: Task<Void, Never>?
    private var background: UIBackgroundTaskIdentifier = .invalid

    func matching(playlist: String, target: RestoreTarget, done: Int, total: Int, outcomes: [ReviewStatus]) {
        let bar = RestoreBar.encode(outcomes.map(Self.cell), total: total)
        show(.init(stage: .matching, done: done, total: total, bar: bar), for: .init(playlist: playlist, target: target.label))
    }

    func writing(playlist: String, target: RestoreTarget, count: Int) {
        show(.init(stage: .creating, done: 0, total: count, bar: ""), for: .init(playlist: playlist, target: target.label))
    }

    func finished(playlist: String, target: RestoreTarget, record: PlaylistRestoreRecord) {
        let added = Int(record.added)
        // Written: what came of it stays on the Lock Screen until it's swiped away (the island lets it go).
        end(.init(stage: record.failed ? .notCreated : .created, done: added, total: added, bar: ""))
    }

    func clear() {
        end(nil)
    }

    /// Ends the restore activities an earlier launch left under way (Yoin stopped mid-restore): nothing
    /// carries them on. What a finished one says stays.
    static func endLeftovers() {
        for activity in Activity<RestoreActivityAttributes>.activities
        where !shown.contains(activity.id) && activity.content.state.stage.underWay {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    static func cell(_ status: ReviewStatus) -> RestoreBar.Cell {
        switch status {
        case .matched, .picked: .matched
        case .uncertain: .toCheck
        default: .notFound
        }
    }

    // MARK: -

    private func show(_ state: RestoreActivityAttributes.ContentState, for attributes: RestoreActivityAttributes) {
        keepRunning()
        if let activity {
            if activity.attributes == attributes {
                pending = state
                return push()
            }
            // Another restore's (this one began afresh): it goes, the new one takes its place.
            end(nil, keepRunning: true)
        } else if refused == attributes {
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled,
              let started = try? Activity.request(attributes: attributes, content: content(state), pushType: nil)
        else {
            // Live Activities off for Yoin, or not allowed now: this stage goes on without one.
            refused = attributes
            return
        }
        activity = started
        Self.shown.insert(started.id)
        lastPush = ContinuousClock.Instant.now
    }

    /// The newest state, at most every [interval]: now if the last went out long enough ago, else when it has.
    private func push() {
        guard trailing == nil else { return }
        let wait = lastPush.map { ($0 + Self.interval) - ContinuousClock.Instant.now } ?? .zero
        guard wait > .zero else { return flush() }
        trailing = Task { [weak self] in
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled, let self else { return }
            self.trailing = nil
            self.flush()
        }
    }

    private func flush() {
        guard let activity, let state = pending else { return }
        pending = nil
        lastPush = ContinuousClock.Instant.now
        let content = content(state)
        Task { await activity.update(content) }
    }

    /// The activity goes: at once ([final] nil), or saying [final] until it's swiped away.
    private func end(_ final: RestoreActivityAttributes.ContentState?, keepRunning: Bool = false) {
        trailing?.cancel()
        trailing = nil
        pending = nil
        refused = nil
        if !keepRunning { letGo() }
        guard let activity else { return }
        self.activity = nil
        Self.shown.remove(activity.id)
        let content = final.map { ActivityContent(state: $0, staleDate: nil) }
        Task { await activity.end(content, dismissalPolicy: final == nil ? .immediate : .default) }
    }

    private func content(_ state: RestoreActivityAttributes.ContentState) -> ActivityContent<RestoreActivityAttributes.ContentState> {
        ActivityContent(state: state, staleDate: Date.now.addingTimeInterval(Self.staleAfter))
    }

    /// The restore isn't cut short the moment Yoin is left: iOS gives it a while (until the system ends it).
    private func keepRunning() {
        guard background == .invalid else { return }
        background = UIApplication.shared.beginBackgroundTask(withName: "playlist-restore") { [weak self] in
            MainActor.assumeIsolated { self?.letGo() }
        }
    }

    private func letGo() {
        guard background != .invalid else { return }
        UIApplication.shared.endBackgroundTask(background)
        background = .invalid
    }
}

#if DEBUG
extension RestoreLiveActivity {
    private static var qa: RestoreLiveActivity?

    /// QA screenshots and recordings (`-qaRestoreActivity matching|creating|created|run`): a restore's Live
    /// Activity over made-up songs, nothing restored. `run` goes through it all, a few songs a second; after
    /// matching it waits for Yoin to be in front again (as a review would) before it writes.
    static func startQADemo() {
        guard let stage = UserDefaults.standard.string(forKey: "qaRestoreActivity") else { return }
        let sink = RestoreLiveActivity()
        qa = sink
        let target = RestoreTarget(id: "qa", label: "Spotify", provider: "spotify")
        let playlist = "Night Drive"
        let outcomes: [ReviewStatus] = (0..<45).map { i -> ReviewStatus in
            if i % 11 == 4 { return ReviewStatus.uncertain }
            if i % 17 == 9 { return ReviewStatus.notfound }
            return i % 13 == 6 ? ReviewStatus.picked : ReviewStatus.matched
        }
        let record = PlaylistRestoreRecord(at: 0, targetProvider: "spotify", targetLabel: "Spotify",
                                           added: 41, skipped: 2, notFound: 2, failed: false)
        switch stage {
        case "matching":
            sink.matching(playlist: playlist, target: target, done: 28, total: 45, outcomes: Array(outcomes.prefix(28)))
        case "creating":
            sink.writing(playlist: playlist, target: target, count: 41)
        case "created":
            sink.writing(playlist: playlist, target: target, count: 41)
            sink.finished(playlist: playlist, target: target, record: record)
        default:
            Task { @MainActor in
                for done in 0...45 {
                    sink.matching(playlist: playlist, target: target, done: done, total: 45, outcomes: Array(outcomes.prefix(done)))
                    try? await Task.sleep(for: .milliseconds(250))
                }
                sink.clear()
                // The write starts from the review in Yoin, so its activity is asked for with Yoin in front
                // (ActivityKit refuses a new one from the background): it waits for the tester to come back.
                while UIApplication.shared.applicationState != .active {
                    try? await Task.sleep(for: .milliseconds(250))
                }
                try? await Task.sleep(for: .seconds(1))
                sink.writing(playlist: playlist, target: target, count: 41)
                try? await Task.sleep(for: .seconds(6))
                sink.finished(playlist: playlist, target: target, record: record)
            }
        }
    }
}
#endif
