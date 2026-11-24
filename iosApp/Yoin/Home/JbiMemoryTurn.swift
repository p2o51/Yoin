import Foundation
import Observation
import UIKit

/// Whose turn Jump Back In's memory 1×2 is (Android ui/home/JbiMemoryTurn.kt, owner 2026-11-20): one more
/// each time the app starts, and each time it comes back after [awayMs] in the background — a player lives
/// in the background for hours, so a cold start alone would hardly ever come. Coming back sooner (a reply to
/// a message) keeps the turn, so nothing changes under the user's eyes. Kept on this device.
@MainActor
@Observable
final class JbiMemoryTurn {
    /// The app's: made when Home first needs it (a cold start), then follows the app in and out.
    static let shared = JbiMemoryTurn(defaults: .standard, observingApp: true)
    /// AWAY_MS: half an hour.
    static let awayMs: Int64 = 30 * 60 * 1000
    private static let key = "jbiMemoryTurn"

    private(set) var turn: Int64 = 0
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let clock: () -> Int64
    @ObservationIgnored private let awayMs: Int64
    @ObservationIgnored private var backgroundAt: Int64?

    init(defaults: UserDefaults, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
         awayMs: Int64 = JbiMemoryTurn.awayMs, observingApp: Bool = false) {
        self.defaults = defaults
        self.clock = clock
        self.awayMs = awayMs
        turn = advance()
        guard observingApp else { return }
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.didEnterBackground() }
        }
        center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.willEnterForeground() }
        }
    }

    /// Android `onStop`.
    func didEnterBackground() { backgroundAt = clock() }

    /// Android `onStart`: back after [awayMs] or more, the next turn.
    func willEnterForeground() {
        guard let away = backgroundAt else { return }
        backgroundAt = nil
        if clock() - away >= awayMs { turn = advance() }
    }

    private func advance() -> Int64 {
        let next = Int64(defaults.integer(forKey: Self.key)) &+ 1
        defaults.set(Int(next), forKey: Self.key)
        return next
    }
}
