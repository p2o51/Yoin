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

    var errorDescription: String? {
        switch self {
        case .unauthorized: "Spotify sign-in expired"
        case .rateLimited(let s): "Spotify is rate limiting requests; try again in \(s)s"
        case .http(let status, let message): message ?? "Spotify request failed (\(status))"
        case .decoding(let path): "Unreadable answer from Spotify (\(path))"
        case .badRequest: "Bad request"
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
final class SpotifyWebAPI: Sendable {
    static let liveBase = URL(string: "https://api.spotify.com/v1")!
    /// The live API, or the QA fake (`-spotifyFake`, DEBUG builds only).
    static var defaultBase: URL { SpotifyFake.base ?? liveBase }
    static let defaultRetryAfter = 5

    private let token: @Sendable () async throws -> String
    private let session: URLSession
    private let base: URL
    private let maxRetries: Int
    private let maxRetryWait: Int
    private let sleep: @Sendable (Int) async throws -> Void
    /// Refreshes the token after a 401 (the request is then tried once more); nil: the 401 is thrown.
    private let refresh: (@Sendable () async throws -> Void)?

    init(token: @escaping @Sendable () async throws -> String,
         refresh: (@Sendable () async throws -> Void)? = nil,
         session: URLSession = .shared,
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

    /// One request; the response body (empty for most writes).
    @discardableResult
    func send(_ method: String, _ path: String, _ query: [String: String] = [:], json: Data? = nil) async throws -> Data {
        let url = try url(path, query)
        var attempt = 0
        var refreshed = false
        while true {
            var req = URLRequest(url: url)
            req.httpMethod = method
            req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
            if let json {
                req.httpBody = json
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else { throw SpotifyAPIError.badRequest }
            switch http.statusCode {
            case 200..<300:
                return data
            case 401:
                guard let refresh, !refreshed else { throw SpotifyAPIError.unauthorized }
                refreshed = true
                try await refresh()
            case 429:
                let wait = max(1, Int(http.value(forHTTPHeaderField: "Retry-After") ?? "") ?? Self.defaultRetryAfter)
                guard attempt < maxRetries, wait <= maxRetryWait else { throw SpotifyAPIError.rateLimited(retryAfter: wait) }
                attempt += 1
                try await sleep(wait)
            case let status:
                throw SpotifyAPIError.http(status: status, message: Self.errorMessage(data))
            }
        }
    }

    func get<T: Decodable>(_ path: String, _ query: [String: String] = [:], as: T.Type = T.self) async throws -> T {
        let data = try await send("GET", path, query)
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
