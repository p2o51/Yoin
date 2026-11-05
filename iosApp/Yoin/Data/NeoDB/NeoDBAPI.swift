import Foundation
import os

/// NeoDB REST client — Android `NeoDBApi.kt` (OpenAPI 0.14.0.5, the subset Yoin uses): dynamic OAuth app
/// registration and code exchange, catalog search for an album's uuid, the user's own Mark (GET / POST)
/// and Review (item-centred GET / POST upsert / DELETE). Calls carry the token as a Bearer header; the
/// instance is whatever the user chose (neodb.social by default).
///
/// No merging here: NeoDBSyncService reads the Mark, merges and posts it back.
struct NeoDBAPI: Sendable {
    var session: URLSession

    init(session: URLSession = NeoDBAPI.defaultSession) {
        self.session = session
    }

    /// Android's OkHttp client: 15 s to connect, 30 s to read.
    static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.httpCookieStorage = nil
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    private static let log = Logger(subsystem: "com.gpo.yoin", category: "NeoDB")

    // MARK: OAuth

    func registerOAuthApp(instance: String, redirectURI: String, clientName: String,
                          website: String) async throws -> NeoDBClientRegistration {
        var request = URLRequest(url: try url(instance, "/api/v1/apps"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setForm(["client_name": clientName, "redirect_uris": redirectURI, "website": website])
        return try await json(request)
    }

    func exchangeOAuthCode(instance: String, clientId: String, clientSecret: String, code: String,
                           redirectURI: String) async throws -> NeoDBTokenResponse {
        var request = URLRequest(url: try url(instance, "/oauth/token"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setForm(["client_id": clientId, "client_secret": clientSecret, "code": code,
                         "redirect_uri": redirectURI, "grant_type": "authorization_code"])
        return try await json(request)
    }

    // MARK: Catalog

    /// `category=music`; an instance that rejects it with a 422 naming the category is asked again with
    /// the legacy `category=album`.
    func searchAlbum(instance: String, token: String, query: String) async throws -> [NeoDBItem] {
        do {
            return try await searchCatalog(instance: instance, token: token, query: query, category: "music")
        } catch let error as NeoDBError where error.code == 422 && error.message.localizedCaseInsensitiveContains("category") {
            Self.log.warning("searchAlbum: category=music rejected — retrying legacy category=album")
            return try await searchCatalog(instance: instance, token: token, query: query, category: "album")
        }
    }

    private func searchCatalog(instance: String, token: String, query: String, category: String) async throws -> [NeoDBItem] {
        let path = "/api/catalog/search?category=\(category)&query=\(NeoDBAPI.formEncode(query))"
        let request = authorized(try url(instance, path), token: token)
        let response: NeoDBSearchResponse = try await json(request)
        return response.data
    }

    // MARK: Mark

    /// The user's Mark on the item; nil when there is none (404 or an empty body).
    func getShelfItem(instance: String, token: String, itemUuid: String) async throws -> NeoDBShelfItem? {
        let request = authorized(try url(instance, "/api/me/shelf/item/\(itemUuid)"), token: token)
        let (data, status) = try await send(request, notFoundIsNil: true)
        guard let data, status != 404, !data.isBlank else { return nil }
        return try decode(data)
    }

    func postShelfMark(instance: String, token: String, itemUuid: String, body: NeoDBShelfMarkRequest) async throws {
        var request = authorized(try url(instance, "/api/me/shelf/item/\(itemUuid)"), token: token)
        request.httpMethod = "POST"
        request.setJSON(try JSONEncoder().encode(body))
        _ = try await send(request)
    }

    // MARK: Review (item-centred: at most one per user per item)

    func getMyReviewForItem(instance: String, token: String, itemUuid: String) async throws -> NeoDBReviewResponse? {
        let request = authorized(try url(instance, "/api/me/review/item/\(itemUuid)"), token: token)
        let (data, status) = try await send(request, notFoundIsNil: true)
        guard let data, status != 404, !data.isBlank else { return nil }
        return try decode(data)
    }

    /// Upsert: creating and rewriting the review are the same call. A body that isn't a review (the
    /// current API answers with a result message) reads as a review without a uuid.
    func postReview(instance: String, token: String, itemUuid: String, body: NeoDBReviewRequest) async throws -> NeoDBReviewResponse {
        var request = authorized(try url(instance, "/api/me/review/item/\(itemUuid)"), token: token)
        request.httpMethod = "POST"
        request.setJSON(try JSONEncoder().encode(body))
        let (data, _) = try await send(request)
        guard let data, !data.isBlank else { return NeoDBReviewResponse() }
        return (try? JSONDecoder().decode(NeoDBReviewResponse.self, from: data)) ?? NeoDBReviewResponse()
    }

    func deleteReview(instance: String, token: String, itemUuid: String) async throws {
        var request = URLRequest(url: try url(instance, "/api/me/review/item/\(itemUuid)"))
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        _ = try await send(request)
    }

    // MARK: Transport

    private func authorized(_ url: URL, token: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    private func url(_ instance: String, _ path: String) throws -> URL {
        var base = instance
        while base.hasSuffix("/") { base.removeLast() }
        let p = path.hasPrefix("/") ? path : "/" + path
        guard let url = URL(string: base + p) else { throw NeoDBError(code: 0, message: "Invalid NeoDB URL") }
        return url
    }

    /// The body and status; a non-2xx answer throws NeoDBError (404 returns when [notFoundIsNil]).
    private func send(_ request: URLRequest, notFoundIsNil: Bool = false) async throws -> (Data?, Int) {
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if notFoundIsNil, status == 404 { return (nil, 404) }
        guard (200..<300).contains(status) else {
            let error = NeoDBAPI.error(status: status, body: data)
            Self.log.warning("request failed: \(request.httpMethod ?? "GET", privacy: .public) \(request.url?.path ?? "", privacy: .public) code=\(status) message=\(error.message, privacy: .public)")
            throw error
        }
        return (data, status)
    }

    private func json<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, _) = try await send(request)
        return try decode(data ?? Data())
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        try JSONDecoder().decode(T.self, from: data)
    }

    /// Android `toNeoDBException`: the server's message (`detail` as text, a list of `{loc, msg}` or one
    /// such object, else `error`), or the body's first 200 characters.
    static func error(status: Int, body: Data) -> NeoDBError {
        let text = String(decoding: body, as: UTF8.self)
        return NeoDBError(code: status, message: parseErrorMessage(body) ?? String(text.prefix(200)), rawBody: text)
    }

    static func parseErrorMessage(_ body: Data) -> String? {
        guard let root = (try? JSONSerialization.jsonObject(with: body, options: [.fragmentsAllowed])) as? [String: Any] else {
            return nil
        }
        if let detail = root["detail"] {
            if let s = detail as? String { return s }
            if let list = detail as? [Any] {
                let messages = list.compactMap(formatDetailEntry)
                if !messages.isEmpty { return messages.joined(separator: " | ") }
            } else if let object = detail as? [String: Any], let m = formatDetailEntry(object) {
                return m
            }
        }
        return root["error"] as? String
    }

    private static func formatDetailEntry(_ entry: Any) -> String? {
        guard let object = entry as? [String: Any] else { return entry as? String }
        let msg = (object["msg"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let loc = (object["loc"] as? [Any])?.map { "\($0)" }.joined(separator: ".")
        switch (msg, loc) {
        case let (m?, l?) where !l.isEmpty: return "\(l): \(m)"
        case let (m?, _): return m
        default: return nil
        }
    }

    /// `application/x-www-form-urlencoded` (Java URLEncoder / OkHttp FormBody): letters, digits and
    /// `-_.*` as is, a space as `+`, everything else as UTF-8 percent escapes.
    static func formEncode(_ value: String) -> String {
        var out = ""
        for byte in value.utf8 {
            switch byte {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"),
                 UInt8(ascii: "0")...UInt8(ascii: "9"), UInt8(ascii: "-"), UInt8(ascii: "_"),
                 UInt8(ascii: "."), UInt8(ascii: "*"):
                out.append(Character(UnicodeScalar(byte)))
            case UInt8(ascii: " "):
                out.append("+")
            default:
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }
}

private extension URLRequest {
    mutating func setForm(_ fields: KeyValuePairs<String, String>) {
        setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        httpBody = Data(fields.map { "\(NeoDBAPI.formEncode($0.key))=\(NeoDBAPI.formEncode($0.value))" }
            .joined(separator: "&").utf8)
    }

    mutating func setJSON(_ data: Data) {
        setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        httpBody = data
    }
}

private extension Data {
    var isBlank: Bool { String(decoding: self, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
