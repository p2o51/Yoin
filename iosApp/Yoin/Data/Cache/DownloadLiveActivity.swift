// Activity isn't Sendable in the SDK: handing it to its own async end/update reads as a data race in Swift 6.
@preconcurrency import ActivityKit
import Foundation
import Network
import UIKit

/// One run of downloads as its Live Activity counts it: the songs waiting when it began, and every song
/// queued while it lasts; a song removed (or another account's, after a switch) leaves it. Pure, tested
/// (DownloadRunTests).
struct DownloadRun: Equatable {
    /// The run's songs (download keys), in the order they joined.
    private(set) var keys: [String] = []

    /// The run follows [songs] (the signed-in account's): the waiting ones join, the gone ones leave.
    mutating func follow(_ songs: [OfflineDownloads.Song]) {
        let present = Set(songs.map(\.key))
        keys.removeAll { !present.contains($0) }
        var known = Set(keys)
        for song in songs where song.state == .queued || song.state == .downloading {
            if known.insert(song.key).inserted { keys.append(song.key) }
        }
    }

    /// What the activity says of the run over [songs]; [partial]: how far the song downloading has got.
    /// [waiting]: why nothing moves (no network, or only mobile data), else nil. Nil for an empty run.
    func state(_ songs: [OfflineDownloads.Song], partial: (key: String, fraction: Double)?,
               waiting: DownloadActivityAttributes.ContentState.Stage?) -> DownloadActivityAttributes.ContentState? {
        guard !keys.isEmpty else { return nil }
        let byKey = Dictionary(songs.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        let run = keys.compactMap { byKey[$0] }
        let done = run.filter { $0.state == .done }.count
        let failed = run.filter { $0.state == .failed }.count
        let underWay = run.contains { $0.state == .queued || $0.state == .downloading }
        let part = partial.flatMap { p in run.contains { $0.key == p.key && $0.state == .downloading } ? p.fraction : nil } ?? 0
        let progress = run.isEmpty ? 0 : min(1, (Double(done + failed) + part) / Double(run.count))
        // The album, playlist or song downloading now; through, the run's first.
        let now = run.first { $0.state == .downloading } ?? run.first { $0.state == .queued } ?? run.first
        let current = now?.collections.last
        var collections = Set<String>()
        for song in run { if let c = song.collections.last { collections.insert(c.key) } }
        return .init(stage: underWay ? (waiting ?? .downloading) : .finished,
                     done: done, total: run.count, failed: failed, progress: progress,
                     title: current?.title ?? "", more: max(0, collections.count - 1))
    }
}

/// Offline downloads' Live Activity (Android YoinDownloadService's progress notification;
/// Widgets/DownloadActivity.swift): started when songs start downloading with Yoin in front (the only time
/// ActivityKit lets one begin, and a download always starts from a tap in Yoin), followed while they
/// download, also with Yoin left whenever iOS lets Yoin run (all along while music plays, and as each song
/// finishes), and ended when nothing waits, saying what came of it for a few minutes. Nothing shows with Live
/// Activities off for Yoin.
@MainActor
final class DownloadLiveActivity {
    static let shared = DownloadLiveActivity()

    /// The fastest the activity follows the downloads.
    static let interval: Duration = .seconds(1)
    /// How long what came of a run stays on the Lock Screen.
    static let finishedFor: TimeInterval = 5 * 60

    private let offline: OfflineDownloads
    private var run = DownloadRun()
    private var activity: Activity<DownloadActivityAttributes>?
    /// This run couldn't have an activity: not asked again until it's over.
    private var refused = false
    private var pending: DownloadActivityAttributes.ContentState?
    private var lastPush: ContinuousClock.Instant?
    private var trailing: Task<Void, Never>?
    private var path: NWPath?
    private let monitor = NWPathMonitor()

    init(offline: OfflineDownloads = .shared) {
        self.offline = offline
    }

    /// Follows the downloads from now on; ends the activity an earlier launch left under way (nothing
    /// carries it on: this launch starts its own once the downloads run again).
    func start() {
        for leftover in Activity<DownloadActivityAttributes>.activities where leftover.content.state.stage.underWay {
            Task { await leftover.end(nil, dismissalPolicy: .immediate) }
        }
        offline.onChange = { [weak self] in self?.update() }
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.path = path
                self?.update()
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.gpo.yoin.download-activity.path"))
        // A run the activity missed (asked for from the background, as a relaunch for the session is) gets
        // one when Yoin comes to the front.
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil,
                                               queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.update() }
        }
        update()
    }

    /// Why nothing moves: no network, or only mobile data (or Low Data Mode) with that not allowed.
    private var waiting: DownloadActivityAttributes.ContentState.Stage? {
        guard let path else { return nil }
        if path.status != .satisfied { return .waitingForNetwork }
        if !offline.overMobileData && (path.isExpensive || path.isConstrained) { return .waitingForWiFi }
        return nil
    }

    private func update() {
        let songs = offline.visible
        run.follow(songs)
        guard let state = run.state(songs, partial: offline.partial, waiting: waiting) else {
            // Everything of the run was removed (or another account signed in): it goes at once.
            return end(nil)
        }
        guard state.stage.underWay else {
            return end(state)
        }
        if activity != nil {
            pending = state
            return push()
        }
        guard !refused, UIApplication.shared.applicationState == .active,
              ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            activity = try Activity.request(attributes: DownloadActivityAttributes(),
                                            content: ActivityContent(state: state, staleDate: nil), pushType: nil)
            lastPush = ContinuousClock.Instant.now
        } catch {
            refused = true
        }
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
        let content = ActivityContent(state: state, staleDate: nil)
        Task { await activity.update(content) }
    }

    /// The run is over: its activity goes at once ([final] nil), or says [final] for a few minutes.
    private func end(_ final: DownloadActivityAttributes.ContentState?) {
        trailing?.cancel()
        trailing = nil
        pending = nil
        refused = false
        run = DownloadRun()
        guard let activity else { return }
        self.activity = nil
        let content = final.map { ActivityContent(state: $0, staleDate: nil) }
        let policy: ActivityUIDismissalPolicy = final == nil ? .immediate : .after(.now.addingTimeInterval(Self.finishedFor))
        Task { await activity.end(content, dismissalPolicy: policy) }
    }
}

#if DEBUG
extension DownloadLiveActivity {
    /// QA screenshots (`-qaDownloadActivity downloading|wifi|network|finished|failed`): the activity over a
    /// made-up run, nothing downloaded.
    static func startQADemo() {
        guard let stage = UserDefaults.standard.string(forKey: "qaDownloadActivity"),
              ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        typealias S = DownloadActivityAttributes.ContentState
        let state: S = switch stage {
        case "wifi": S(stage: .waitingForWiFi, done: 4, total: 12, failed: 0, progress: 0.36, title: "Blue Rev", more: 0)
        case "network": S(stage: .waitingForNetwork, done: 4, total: 12, failed: 0, progress: 0.36, title: "Blue Rev", more: 0)
        case "finished": S(stage: .finished, done: 41, total: 41, failed: 0, progress: 1, title: "Night Drive", more: 2)
        case "failed": S(stage: .finished, done: 38, total: 41, failed: 3, progress: 1, title: "Night Drive", more: 2)
        default: S(stage: .downloading, done: 7, total: 12, failed: 0, progress: 0.62, title: "Blue Rev", more: 2)
        }
        let made = try? Activity.request(attributes: DownloadActivityAttributes(),
                                         content: ActivityContent(state: state, staleDate: nil), pushType: nil)
        if state.stage == .finished, let made {
            Task { await made.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: .default) }
        }
    }
}
#endif
