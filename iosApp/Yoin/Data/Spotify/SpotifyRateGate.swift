import Foundation

/// One account's Spotify request gate (Android SpotifyRateLimitGate, plus a cap on requests in flight):
/// a 429 sets a backoff that every later request of the account sees, so a library read, a heart fill and
/// a search don't each run into the same limit; and at most [maxConcurrent] reads go out at once, so a
/// long playlist's heart fill doesn't set off the limit by itself.
actor SpotifyRateGate {
    /// Android MAX_BACKOFF_SECONDS: a day at most, whatever Retry-After says.
    static let maxBackoff = 24 * 60 * 60

    private let now: @Sendable () -> Date
    private let maxConcurrent: Int
    private var backoffUntil: Date?
    private var running = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []

    init(maxConcurrent: Int = 3, now: @escaping @Sendable () -> Date = Date.init) {
        self.maxConcurrent = max(1, maxConcurrent)
        self.now = now
    }

    /// Whole seconds until the backoff ends (rounded up); 0 when requests may go.
    func remaining() -> Int {
        guard let until = backoffUntil else { return 0 }
        let left = until.timeIntervalSince(now())
        if left <= 0 { backoffUntil = nil; return 0 }
        return Int(left.rounded(.up))
    }

    /// A 429 answered with [retryAfter] seconds: the later of it and any backoff already set.
    func recordBackoff(_ retryAfter: Int) {
        let until = now().addingTimeInterval(TimeInterval(min(max(1, retryAfter), Self.maxBackoff)))
        if let have = backoffUntil, have >= until { return }
        backoffUntil = until
    }

    func clear() { backoffUntil = nil }

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
