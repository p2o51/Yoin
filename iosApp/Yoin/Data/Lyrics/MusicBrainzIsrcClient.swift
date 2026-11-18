import Foundation

/// Fetches the localized/canonical release title + artist names recorded under an ISRC from
/// MusicBrainz (`GET /ws/2/isrc/{isrc}?fmt=json&inc=artist-credits`).
///
/// MusicBrainz asks for at most one request per second and an identifying `User-Agent`; requests here
/// take turns through an actor queue, cache hits and 404 misses in a 256-entry LRU cache, and leave
/// transient network/HTTP errors uncached so callers can retry later.
actor MusicBrainzIsrcClient {
    static let shared = MusicBrainzIsrcClient()

    /// Matches Android's `MusicBrainzIsrcClient.USER_AGENT`.
    nonisolated static let userAgent = "Yoin/1 ( https://github.com/p2o51/Yoin )"
    nonisolated static let lengthToleranceMs: Int64 = 3_000
    nonisolated static let defaultMinGapSeconds: TimeInterval = 1.1
    nonisolated static let defaultMaxEntries = 256

    enum FetchError: Error, Equatable, Sendable {
        case invalidURL
        case httpStatus(Int)
    }

    private struct Recording: Sendable {
        let title: String
        let artist: String
        let lengthMs: Int64?
    }

    private struct SeenName: Hashable {
        let title: String
        let artist: String
    }

    private let session: URLSession
    private let baseURL: URL
    private let maxEntries: Int
    private let minGapSeconds: TimeInterval
    private let clock: @Sendable () -> TimeInterval
    private let sleeper: @Sendable (TimeInterval) async throws -> Void

    private var lastRequestAt: TimeInterval?
    private var turnBusy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var cache: [String: [Recording]] = [:]
    private var lruOrder: [String] = []

    init(
        session: URLSession = .shared,
        baseURL: URL = URL(string: "https://musicbrainz.org")!,
        maxEntries: Int = MusicBrainzIsrcClient.defaultMaxEntries,
        minGapSeconds: TimeInterval = MusicBrainzIsrcClient.defaultMinGapSeconds,
        clock: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
        sleeper: @escaping @Sendable (TimeInterval) async throws -> Void = { seconds in
            try await Task.sleep(for: .seconds(seconds))
        }
    ) {
        self.session = session
        self.baseURL = baseURL
        self.maxEntries = max(1, maxEntries)
        self.minGapSeconds = max(0, minGapSeconds)
        self.clock = clock
        self.sleeper = sleeper
    }

    /// Returns ordered, deduplicated `(title, artist)` pairs recorded under `isrc`:
    /// recordings whose duration matches within ±3000 ms come first (when `durationMs > 0`),
    /// followed by unknown-length fitting candidates for fallback. Invalid ISRCs or 404 misses
    /// return `[]` cleanly; network and non-404 HTTP failures throw without caching.
    func names(isrc: String, durationMs: Int64? = nil) async throws -> [(title: String, artist: String)] {
        guard let code = Self.normalizedIsrc(isrc) else { return [] }
        if let cached = readCache(code) {
            return Self.selectNames(from: cached, durationMs: durationMs)
        }

        await acquireTurn()
        defer { releaseTurn() }
        try Task.checkCancellation()

        if let cached = readCache(code) {
            return Self.selectNames(from: cached, durationMs: durationMs)
        }

        if let lastRequestAt {
            let remaining = (lastRequestAt + minGapSeconds) - clock()
            if remaining > 0 {
                try await sleeper(remaining)
            }
        }
        try Task.checkCancellation()

        let recordings: [Recording]
        do {
            recordings = try await fetchRecordings(code: code)
            lastRequestAt = clock()
        } catch {
            lastRequestAt = clock()
            throw error
        }

        writeCache(recordings, for: code)
        return Self.selectNames(from: recordings, durationMs: durationMs)
    }

    private func fetchRecordings(code: String) async throws -> [Recording] {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw FetchError.invalidURL
        }
        let basePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = "\(basePath)/ws/2/isrc/\(code)"
        components.queryItems = [
            URLQueryItem(name: "fmt", value: "json"),
            URLQueryItem(name: "inc", value: "artist-credits"),
        ]
        guard let url = components.url else {
            throw FetchError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw FetchError.httpStatus(-1)
        }
        if http.statusCode == 404 {
            return []
        }
        guard (200..<300).contains(http.statusCode) else {
            throw FetchError.httpStatus(http.statusCode)
        }
        return Self.parseRecordings(data)
    }

    private func readCache(_ code: String) -> [Recording]? {
        guard let entry = cache[code] else { return nil }
        touchLRU(code)
        return entry
    }

    private func writeCache(_ value: [Recording], for code: String) {
        cache[code] = value
        touchLRU(code)
        while lruOrder.count > maxEntries {
            let oldest = lruOrder.removeFirst()
            cache.removeValue(forKey: oldest)
        }
    }

    private func touchLRU(_ code: String) {
        if let idx = lruOrder.firstIndex(of: code) {
            lruOrder.remove(at: idx)
        }
        lruOrder.append(code)
    }

    private func acquireTurn() async {
        if !turnBusy {
            turnBusy = true
            return
        }
        await withCheckedContinuation { cont in
            waiters.append(cont)
        }
    }

    private func releaseTurn() {
        if waiters.isEmpty {
            turnBusy = false
        } else {
            let next = waiters.removeFirst()
            next.resume()
        }
    }

    nonisolated static func normalizedIsrc(_ raw: String) -> String? {
        let code = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard code.range(of: "^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$", options: .regularExpression) != nil else {
            return nil
        }
        return code
    }

    private nonisolated static func parseRecordings(_ data: Data) -> [Recording] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = root["recordings"] as? [[String: Any]]
        else { return [] }

        return list.compactMap { rec in
            let title = (rec["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let credits = (rec["artist-credit"] as? [[String: Any]]) ?? []
            let firstCredit = credits.first
            let artistRaw = (firstCredit?["name"] as? String)
                ?? ((firstCredit?["artist"] as? [String: Any])?["name"] as? String)
                ?? ""
            let artist = artistRaw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty, !artist.isEmpty else { return nil }

            let lengthMs: Int64?
            if let num = rec["length"] as? NSNumber,
               CFGetTypeID(num) != CFBooleanGetTypeID()
            {
                lengthMs = num.int64Value
            } else {
                lengthMs = nil
            }
            return Recording(title: title, artist: artist, lengthMs: lengthMs)
        }
    }

    private nonisolated static func selectNames(
        from recordings: [Recording],
        durationMs: Int64?
    ) -> [(title: String, artist: String)] {
        guard !recordings.isEmpty else { return [] }

        let ordered: [Recording]
        if let targetMs = durationMs, targetMs > 0 {
            let knownMatching = recordings.filter { rec in
                guard let len = rec.lengthMs else { return false }
                return abs(len - targetMs) <= lengthToleranceMs
            }
            let unknownLength = recordings.filter { $0.lengthMs == nil }
            ordered = knownMatching + unknownLength
        } else {
            ordered = recordings
        }

        var seen = Set<SeenName>()
        var result: [(title: String, artist: String)] = []
        result.reserveCapacity(ordered.count)
        for rec in ordered {
            let key = SeenName(title: rec.title, artist: rec.artist)
            if seen.insert(key).inserted {
                result.append((title: rec.title, artist: rec.artist))
            }
        }
        return result
    }
}
