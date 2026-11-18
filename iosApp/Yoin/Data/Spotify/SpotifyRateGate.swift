import Foundation

/// One account's Spotify request gate (Android SpotifyRateLimitGate, plus a cap on requests in flight):
/// a 429 sets a backoff that every later request of the account sees, so a library read, a heart fill and
/// a search don't each run into the same limit; and at most [maxConcurrent] reads go out at once, so a
/// long playlist's heart fill doesn't set off the limit by itself.
actor SpotifyRateGate {
    /// Android MAX_BACKOFF_SECONDS: a day at most, whatever Retry-After says.
    static let maxBackoff = 24 * 60 * 60

    private final class DefaultsStorage: @unchecked Sendable {
        let defaults: UserDefaults
        init(_ defaults: UserDefaults) { self.defaults = defaults }
    }

    private final class AccountRegistry: @unchecked Sendable {
        let lock = NSLock()
        var byAccount: [String: [(storage: DefaultsStorage, gate: SpotifyRateGate)]] = [:]
    }

    private static let accountRegistry = AccountRegistry()

    /// Shared persisted gate per Spotify account so a 429 cooldown survives app restarts.
    static func forAccount(_ id: String, defaults: UserDefaults = .standard) -> SpotifyRateGate {
        let box = DefaultsStorage(defaults)
        accountRegistry.lock.lock()
        defer { accountRegistry.lock.unlock() }
        if let existing = accountRegistry.byAccount[id]?.first(where: { $0.storage.defaults === defaults })?.gate {
            return existing
        }
        let gate = SpotifyRateGate(storageKey: "spotifyRateGate.\(id)", storage: box)
        accountRegistry.byAccount[id, default: []].append((box, gate))
        return gate
    }

    private let now: @Sendable () -> Date
    private let maxConcurrent: Int
    private let storageKey: String?
    private let storage: DefaultsStorage
    private var backoffUntil: Date?
    private var backoffGen: UInt64 = 0
    private var running = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(
        maxConcurrent: Int = 3,
        now: @escaping @Sendable () -> Date = Date.init,
        storageKey: String? = nil,
        defaults: UserDefaults = .standard
    ) {
        self.init(maxConcurrent: maxConcurrent, now: now, storageKey: storageKey, storage: DefaultsStorage(defaults))
    }

    private init(
        maxConcurrent: Int = 3,
        now: @escaping @Sendable () -> Date = Date.init,
        storageKey: String? = nil,
        storage: DefaultsStorage
    ) {
        self.maxConcurrent = max(1, maxConcurrent)
        self.now = now
        self.storageKey = storageKey
        self.storage = storage
        if let storageKey, let raw = storage.defaults.object(forKey: storageKey) as? Double, raw > 0 {
            let current = now()
            let left = raw - current.timeIntervalSince1970
            if left > 0 {
                self.backoffUntil = current.addingTimeInterval(min(left, TimeInterval(Self.maxBackoff)))
                self.backoffGen = 1
            } else {
                storage.defaults.removeObject(forKey: storageKey)
            }
        }
    }

    private func activeRemaining() -> TimeInterval? {
        guard let until = backoffUntil else { return nil }
        let left = until.timeIntervalSince(now())
        if left <= 0 {
            backoffUntil = nil
            if let storageKey { storage.defaults.removeObject(forKey: storageKey) }
            return nil
        }
        return left
    }

    /// Whole seconds until the backoff ends (rounded up); 0 when requests may go.
    func remaining() -> Int {
        guard let left = activeRemaining() else { return 0 }
        return Int(left.rounded(.up))
    }

    /// Current remaining seconds alongside a monotonic generation counter for backoff updates.
    func state() -> (remaining: Int, gen: UInt64) {
        (remaining(), backoffGen)
    }

    /// A 429 answered with [retryAfter] seconds: the later of it and any backoff already set.
    @discardableResult
    func recordBackoff(_ retryAfter: Int) -> UInt64 {
        let until = now().addingTimeInterval(TimeInterval(min(max(1, retryAfter), Self.maxBackoff)))
        if let have = backoffUntil, have >= until { return backoffGen }
        backoffUntil = until
        backoffGen &+= 1
        if let storageKey { storage.defaults.set(until.timeIntervalSince1970, forKey: storageKey) }
        return backoffGen
    }

    func clear() {
        backoffUntil = nil
        backoffGen &+= 1
        if let storageKey { storage.defaults.removeObject(forKey: storageKey) }
    }

    /// Waits for a free slot; pair each call with one `release()`.
    func acquire() async {
        if running < maxConcurrent {
            running += 1
            return
        }
        await withCheckedContinuation { waiting.append($0) }
    }

    func release() {
        if waiting.isEmpty {
            running = max(0, running - 1)
        } else {
            // The slot passes straight to the next in line.
            waiting.removeFirst().resume()
        }
    }

    /// Requests in flight now (tests).
    var inFlight: Int { running }
}
