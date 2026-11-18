import Foundation

/// What a Spotify Web API call can fail with, beyond URLSession's own errors.
enum SpotifyAPIError: Error, LocalizedError, Equatable {
    /// 401: the token is dead or expired. The caller owns OAuth: refresh and retry once.
    case unauthorized
    /// 429 that outlasted the retries ([retryAfter] seconds, from `Retry-After`).
    case rateLimited(retryAfter: Int)
    /// Any other HTTP failure, with Spotify's own message when it sent one.
    case http(status: Int, message: String?)
    /// A 2xx body that doesn't fit the expected shape.
    case decoding(String)
    case badRequest

    var isNotFound: Bool { if case .http(404, _) = self { true } else { false } }
    var isForbidden: Bool { if case .http(403, _) = self { true } else { false } }
    /// 404 NO_ACTIVE_DEVICE: a player command with no device to run on.
    var isNoActiveDevice: Bool {
        if case .http(404, let m) = self { (m ?? "").localizedCaseInsensitiveContains("active device") } else { false }
    }

    /// A wait as the message says it: seconds under two minutes, else minutes, else hours (rounded up).
    static func waitText(_ seconds: Int) -> String {
        let f = DateComponentsFormatter()
        f.unitsStyle = .short
        f.maximumUnitCount = 1
        f.allowedUnits = seconds < 120 ? [.second] : seconds < 2 * 3600 ? [.minute] : [.hour]
        let rounded = seconds < 120 ? max(1, seconds) : seconds < 2 * 3600 ? (seconds + 59) / 60 * 60 : (seconds + 3599) / 3600 * 3600
        return f.string(from: TimeInterval(rounded)) ?? "\(seconds)s"
    }

    var errorDescription: String? {
        switch self {
        case .unauthorized: String(localized: "Spotify sign-in expired")
        case .rateLimited(let s): String(localized: "Spotify is busy. Try again in \(Self.waitText(s))")
        case .http(let status, let message): message ?? String(localized: "Spotify request failed (\(status))")
        case .decoding(let path): String(localized: "Unreadable answer from Spotify (\(path))")
        case .badRequest: String(localized: "Bad request")
        }
    }
}

/// A thin URLSession client for `https://api.spotify.com/v1` (Android
/// SpotifyApiClient). No OAuth: [token] hands out a valid access token per
/// request and the caller refreshes it; a 401 comes back as
/// `SpotifyAPIError.unauthorized` so it can refresh and call again once.
///
/// 429: waits `Retry-After` seconds (default 5, at most [maxRetryWait]) and
/// asks again up to [maxRetries] times; a longer wait, or one that keeps
/// coming, throws `.rateLimited` so the screen can say so instead of hanging.
/// The wait is the account's ([gate], one per client): a request that comes
/// while it runs waits it out when short, else fails at once with the time left.
/// Reads go out at most three at a time; player calls skip the line, wait out at most one short 429,
/// and fail at once while a long limit lasts.
final class SpotifyWebAPI: Sendable {
    static let liveBase = URL(string: "https://api.spotify.com/v1")!
    /// The live API, or the QA fake (`-spotifyFake`, DEBUG builds only).
    static var defaultBase: URL { SpotifyFake.base ?? liveBase }
    static let defaultRetryAfter = 5

    /// Spotify's own connection (audit 11-15 #74, Android's 15 s connect/read timeouts): a request on a
    /// weak signal gives up after 15 s instead of URLSession's default 60, and doesn't sit waiting for the
    /// network to come back. Shared with SpotifyAuth's token and `me` reads.
    static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 60
        cfg.waitsForConnectivity = false
        return URLSession(configuration: cfg)
    }()

    private let token: @Sendable () async throws -> String
    private let session: URLSession
    private let base: URL
    private let maxRetries: Int
    private let maxRetryWait: Int
    private let sleep: @Sendable (Int) async throws -> Void
    /// Refreshes the token after a 401 (the request is then tried once more); nil: the 401 is thrown.
    private let refresh: (@Sendable () async throws -> Void)?
    let gate: SpotifyRateGate

    init(token: @escaping @Sendable () async throws -> String,
         refresh: (@Sendable () async throws -> Void)? = nil,
         gate: SpotifyRateGate = SpotifyRateGate(),
         session: URLSession = SpotifyWebAPI.session,
         base: URL = SpotifyWebAPI.defaultBase,
         maxRetries: Int = 2,
         maxRetryWait: Int = 15,
         sleep: @escaping @Sendable (Int) async throws -> Void = { try await Task.sleep(for: .seconds($0)) }) {
        self.token = token
        self.session = session
        self.base = base
        self.maxRetries = maxRetries
        self.maxRetryWait = maxRetryWait
        self.sleep = sleep
        self.refresh = refresh
        self.gate = gate
    }

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    // MARK: Requests

    /// [path] is relative to the base (`me/tracks`) or a full URL (a page's `next` link).
    func url(_ path: String, _ query: [String: String] = [:]) throws -> URL {
        let full = path.hasPrefix("https://") || path.hasPrefix("http://") ? path : base.absoluteString + "/" + path
        guard var c = URLComponents(string: full) else { throw SpotifyAPIError.badRequest }
        if !query.isEmpty {
            // A `next` link already carries the options it needs; only add what it lacks.
            let have = Set(c.queryItems?.map(\.name) ?? [])
            let extra = query.filter { !have.contains($0.key) }.sorted { $0.key < $1.key }
            c.queryItems = (c.queryItems ?? []) + extra.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = c.url else { throw SpotifyAPIError.badRequest }
        return url
    }

    /// Asked before a command goes out and before each retry: false drops it (`SpotifyCommandDropped`).
    typealias StillWanted = @Sendable () async -> Bool

    /// One request; the response body (empty for most writes). [still] is asked before each try.
    @discardableResult
    func send(_ method: String, _ path: String, _ query: [String: String] = [:], json: Data? = nil,
              headers: [String: String] = [:], while still: StillWanted? = nil) async throws -> Data {
        let url = try url(path, query)
        var attempt = 0
        var refreshed = false
        // Player calls keep their own timing (a skip mustn't queue behind a library read). Recently played
        // is a library read that only lives under me/player.
        let player = Self.isPlayerPath(path)
        let gated = !player
        // audit 11-15 #67: a player call waits out at most one short 429 (a pause that sat through two
        // 15 s waits answered half a minute late), and none during a long limit.
        let retries = player ? min(maxRetries, 1) : maxRetries
        let retryWait = player ? min(maxRetryWait, Self.playerRetryWait) : maxRetryWait
        // Generation of the backoff this call already slept out, so mocked clocks advance once without
        // letting a new concurrent 429 slip past.
        var sleptGen: UInt64? = nil
        while true {
            if let still, await !still() { throw SpotifyCommandDropped() }
            let (left, gen) = await gate.state()
            if gated {
                if left > 0, sleptGen != gen {
                    guard left <= maxRetryWait else { throw SpotifyAPIError.rateLimited(retryAfter: left) }
                    try await sleep(left)
                    sleptGen = gen
                    continue
                }
            } else if player {
                // P1-9: match Android's SpotifyApiClient — refuse all player polls and commands whenever the
                // shared rate gate has any active backoff (except after this call's own single short-429 sleep).
                if left > 0, sleptGen != gen {
                    throw SpotifyAPIError.rateLimited(retryAfter: left)
                }
            }
            sleptGen = nil
            var req = URLRequest(url: url)
            req.httpMethod = method
            for (field, value) in headers {
                req.setValue(value, forHTTPHeaderField: field)
            }
            let bearer = try await token()
            // The token may have waited on a refresh: asked again right before the request goes out.
            if let still, await !still() { throw SpotifyCommandDropped() }
            req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
            if let json {
                req.httpBody = json
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            let t0 = Date()
            if gated { await gate.acquire() }
            let data: Data, response: URLResponse
            do {
                (data, response) = try await session.data(for: req)
                if gated { await gate.release() }
            } catch {
                if gated { await gate.release() }
                throw error
            }
            guard let http = response as? HTTPURLResponse else { throw SpotifyAPIError.badRequest }
            if path.hasPrefix("me/player") {
                PlayTrace.log("API \(method) \(url.path)?\(url.query ?? "") → \(http.statusCode) in \(String(format: "%.2f", Date().timeIntervalSince(t0)))s retry-after=\(http.value(forHTTPHeaderField: "Retry-After") ?? "-")\(http.statusCode >= 300 ? " " + (Self.errorMessage(data) ?? "") : "")")
            }
            switch http.statusCode {
            case 200..<300:
                return data
            case 401:
                guard let refresh, !refreshed else { throw SpotifyAPIError.unauthorized }
                refreshed = true
                try await refresh()
            case 429:
                let wait = max(1, Int(http.value(forHTTPHeaderField: "Retry-After") ?? "") ?? Self.defaultRetryAfter)
                let recordedGen = await gate.recordBackoff(wait)
                guard attempt < retries, wait <= retryWait else { throw SpotifyAPIError.rateLimited(retryAfter: wait) }
                attempt += 1
                try await sleep(wait)
                sleptGen = recordedGen
            case let status:
                throw SpotifyAPIError.http(status: status, message: Self.errorMessage(data))
            }
        }
    }

    /// The longest 429 wait a player call sits through before failing (once).
    static let playerRetryWait = 5

    /// A player call (state, queue, devices, commands): outside the read slots, its own retry rule.
    static func isPlayerPath(_ path: String) -> Bool {
        path.hasPrefix("me/player") && !path.hasPrefix("me/player/recently-played")
    }

    func get<T: Decodable>(_ path: String, _ query: [String: String] = [:],
                           headers: [String: String] = [:], as: T.Type = T.self) async throws -> T {
        let data = try await send("GET", path, query, headers: headers)
        do { return try Self.decoder.decode(T.self, from: data) } catch {
            throw SpotifyAPIError.decoding(path)
        }
    }

    /// Every page of an offset list, following `next`, up to [cap] items. [onPage]
    /// gets what has arrived so far after each page (a long library shows as it comes).
    /// A `next` link seen before is refused: the list is whole or it fails.
    /// Main-actor, so [onPage] (a screen's setter) runs where the screen lives.
    @MainActor
    func all<Item: Decodable & Hashable & Sendable>(
        _ path: String, _ query: [String: String] = [:], of: Item.Type = Item.self, cap: Int,
        onPage: (([Item]) -> Void)? = nil
    ) async throws -> [Item] {
        var out: [Item] = []
        var visited = Set<String>()
        var page = try await get(path, query, as: SpotifyDTO.Paging<Item>.self)
        out += page.items
        while out.count < cap, let next = page.next {
            guard visited.insert(next).inserted else { throw SpotifyAPIError.decoding("repeated page") }
            onPage?(out)
            page = try await get(next, query, as: SpotifyDTO.Paging<Item>.self)
            out += page.items
        }
        return Array(out.prefix(cap))
    }

    /// Spotify's own message from an error body: `{"error":{"status":403,"message":"…"}}`,
    /// or the OAuth form `{"error":"…","error_description":"…"}`.
    static func errorMessage(_ data: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let e = root["error"] as? [String: Any], let m = e["message"] as? String, !m.isEmpty { return m }
        if let d = root["error_description"] as? String, !d.isEmpty { return d }
        return root["error"] as? String
    }
}

/// A command for a song that is no longer the one playing, not sent.
struct SpotifyCommandDropped: Error {}
