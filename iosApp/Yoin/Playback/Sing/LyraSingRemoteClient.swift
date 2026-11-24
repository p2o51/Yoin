import CryptoKit
import Foundation

/// Subsonic/Lyra `u/t/s` connection parameters for Sing requests (non-admin playback credentials, no Bearer).
struct LyraSingServerConfig: Equatable, Sendable {
    let baseURL: URL
    let username: String
    let password: String
    let clientName: String

    init(baseURL: URL, username: String, password: String, clientName: String = "Yoin-iOS") {
        self.baseURL = baseURL
        self.username = username
        self.password = password
        self.clientName = clientName
    }

    func authQueryItems(salt: String = Self.randomSalt()) -> [URLQueryItem] {
        let token = Self.md5Hex(password + salt)
        return [
            URLQueryItem(name: "u", value: username),
            URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt),
            URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: clientName),
            URLQueryItem(name: "f", value: "json"),
        ]
    }

    static func randomSalt() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12)).lowercased()
    }

    static func md5Hex(_ input: String) -> String {
        Insecure.MD5.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// Remote Sing client talking to Lyra's locked `/api/v1/sing/{song_id}/...` contract:
/// - `POST /api/v1/sing/{song_id}/prepare` (returns `ready` + `stream_url`, or `processing` + `eta_s`)
/// - `GET  /api/v1/sing/{song_id}/status`  (SSE `Accept: text/event-stream` or JSON poll fallback)
/// - `GET  /api/v1/sing/{song_id}/quad.m4a` (HTTP 206 Range with `u/t/s` query auth)
final class LyraSingRemoteClient: SingStreamProvider, @unchecked Sendable {
    private let config: LyraSingServerConfig
    private let session: URLSession

    init(config: LyraSingServerConfig, session: URLSession = .shared) {
        self.config = config
        self.session = session
    }

    func prepare(songId: String, localAudioURL: URL? = nil, tier: String? = nil) -> AsyncStream<SingState> {
        AsyncStream { continuation in
            let task = Task {
                await self.run(songId: songId, tier: tier, continuation: continuation)
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Builds an authenticated HTTP 206 Range `quad.m4a` stream URL (carrying Subsonic `u/t/s` query params).
    func quadStreamURL(songId: String, streamPath: String? = nil, tier: String? = nil) -> URL? {
        let path: String
        if let streamPath, !streamPath.isEmpty {
            path = streamPath.hasPrefix("/") ? streamPath : "/\(streamPath)"
        } else {
            let encoded = songId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? songId
            path = "/api/v1/sing/\(encoded)/quad.m4a"
        }
        var extra: [URLQueryItem] = []
        if let tier, !tier.isEmpty { extra.append(URLQueryItem(name: "tier", value: tier)) }
        return makeURL(path: path, extraQuery: extra)
    }

    private func run(
        songId: String,
        tier: String?,
        continuation: AsyncStream<SingState>.Continuation
    ) async {
        defer { continuation.finish() }
        let encoded = songId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? songId
        var extra: [URLQueryItem] = []
        if let tier, !tier.isEmpty { extra.append(URLQueryItem(name: "tier", value: tier)) }

        guard let prepareURL = makeURL(path: "/api/v1/sing/\(encoded)/prepare", extraQuery: extra) else {
            continuation.yield(.failed(.serverError("Invalid Lyra server URL")))
            return
        }

        var req = URLRequest(url: prepareURL)
        req.httpMethod = "POST"
        req.timeoutInterval = 15

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                continuation.yield(.failed(.serverError("No HTTP response")))
                return
            }
            guard (200...299).contains(http.statusCode) else {
                continuation.yield(.failed(.serverError("HTTP \(http.statusCode)")))
                return
            }
            let payload = try JSONDecoder().decode(StatusPayload.self, from: data)
            if handlePayload(payload, songId: songId, tier: tier, continuation: continuation) {
                return
            }
            // Subscribe to SSE `/status?stream=1` until ready or failed (falling back to polling if SSE closes early).
            await streamOrPollStatus(songId: songId, encodedId: encoded, tier: tier, continuation: continuation)
        } catch is CancellationError {
            return
        } catch {
            continuation.yield(.failed(.serverError(error.localizedDescription)))
        }
    }

    private func streamOrPollStatus(
        songId: String,
        encodedId: String,
        tier: String?,
        continuation: AsyncStream<SingState>.Continuation
    ) async {
        var baseQuery: [URLQueryItem] = []
        if let tier, !tier.isEmpty { baseQuery.append(URLQueryItem(name: "tier", value: tier)) }
        guard let sseURL = makeURL(path: "/api/v1/sing/\(encodedId)/status", extraQuery: baseQuery + [URLQueryItem(name: "stream", value: "1")]),
              let pollURL = makeURL(path: "/api/v1/sing/\(encodedId)/status", extraQuery: baseQuery) else {
            continuation.yield(.failed(.serverError("Invalid status URL")))
            return
        }

        var sseReq = URLRequest(url: sseURL)
        sseReq.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        sseReq.timeoutInterval = 180

        do {
            let (bytes, response) = try await session.bytes(for: sseReq)
            if let http = response as? HTTPURLResponse,
               (200...299).contains(http.statusCode),
               (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased().contains("text/event-stream") {
                for try await line in bytes.lines {
                    try Task.checkCancellation()
                    let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard trimmed.hasPrefix("data:") else { continue }
                    let jsonText = trimmed.dropFirst(5).trimmingCharacters(in: .whitespaces)
                    guard let jsonData = jsonText.data(using: .utf8),
                          let payload = try? JSONDecoder().decode(StatusPayload.self, from: jsonData) else { continue }
                    if handlePayload(payload, songId: songId, tier: tier, continuation: continuation) {
                        return
                    }
                }
            }
        } catch is CancellationError {
            return
        } catch {
            // Fall through to one-shot JSON polling below if SSE is not supported or dropped transiently.
        }

        // Bounded JSON polling fallback (1s cadence, max 90s).
        for _ in 0..<90 {
            if Task.isCancelled { return }
            do {
                var pollReq = URLRequest(url: pollURL)
                pollReq.setValue("application/json", forHTTPHeaderField: "Accept")
                pollReq.timeoutInterval = 10
                let (data, response) = try await session.data(for: pollReq)
                if let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                   let payload = try? JSONDecoder().decode(StatusPayload.self, from: data) {
                    if handlePayload(payload, songId: songId, tier: tier, continuation: continuation) {
                        return
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                continuation.yield(.failed(.serverError(error.localizedDescription)))
                return
            }
            try? await Task.sleep(for: .seconds(1))
        }
        continuation.yield(.failed(.serverError("Timed out waiting for Lyra Sing")))
    }

    /// Returns `true` when terminal (`ready`, `failed`, or `none`).
    private func handlePayload(
        _ payload: StatusPayload,
        songId: String,
        tier: String?,
        continuation: AsyncStream<SingState>.Continuation
    ) -> Bool {
        let effective = (payload.state ?? payload.status).lowercased()
        switch effective {
        case "ready":
            if let url = quadStreamURL(songId: songId, streamPath: payload.streamUrl, tier: tier) {
                continuation.yield(.ready(source: .remoteM4a(url)))
            } else {
                continuation.yield(.failed(.serverError("Invalid quad stream URL")))
            }
            return true
        case "failed", "error":
            continuation.yield(.failed(.serverError(payload.error ?? "Server separation failed")))
            return true
        case "none":
            continuation.yield(.failed(.serverError("Track is not prepared on server")))
            return true
        case "queued":
            continuation.yield(.preparing(stage: .queued, etaSeconds: payload.etaS))
            return false
        case "encoding":
            continuation.yield(.preparing(stage: .encoding, etaSeconds: payload.etaS))
            return false
        default:
            let stage: SingStage = (payload.stage?.lowercased() == "encoding") ? .encoding : .separating
            continuation.yield(.preparing(stage: stage, etaSeconds: payload.etaS))
            return false
        }
    }

    private func makeURL(path: String, extraQuery: [URLQueryItem]) -> URL? {
        guard var components = URLComponents(url: config.baseURL, resolvingAgainstBaseURL: false) else { return nil }
        let basePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = basePath + path
        components.queryItems = config.authQueryItems() + extraQuery
        return components.url
    }

    private struct StatusPayload: Decodable {
        let status: String
        let state: String?
        let stage: String?
        let streamUrl: String?
        let etaS: Int?
        let error: String?

        enum CodingKeys: String, CodingKey {
            case status
            case state
            case stage
            case streamUrl = "stream_url"
            case etaS = "eta_s"
            case error
        }
    }
}

/// Top-level iOS Sing router enforcing 51's source priority:
/// 1. Spotify / Apple Music (commercial DRM) → `.unavailable` (UI hides Sing button)
/// 2. Connected Lyra server online → `LyraSingRemoteClient` (`/api/v1/sing/{id}/quad.m4a`)
/// 3. Pure Navidrome or Lyra offline with local audio cache → `SingCoreMLSeparator` (`Lyra-Sing-Lite`)
final class SingCoordinator: @unchecked Sendable {
    enum TrackOrigin: Sendable {
        case commercialDRM
        case navidrome(songId: String, localAudioURL: URL?)
        case lyra(songId: String, localAudioURL: URL?, serverOnline: Bool)
    }

    private let remoteProvider: SingStreamProvider?
    private let localProvider: SingStreamProvider

    init(remoteProvider: SingStreamProvider?, localProvider: SingStreamProvider) {
        self.remoteProvider = remoteProvider
        self.localProvider = localProvider
    }

    static func supportsSing(origin: TrackOrigin) -> Bool {
        if case .commercialDRM = origin { return false }
        return true
    }

    func prepare(origin: TrackOrigin, tier: String? = nil) -> AsyncStream<SingState> {
        switch origin {
        case .commercialDRM:
            return AsyncStream { c in
                c.yield(.unavailable)
                c.finish()
            }
        case .navidrome(let songId, let localURL):
            return localProvider.prepare(songId: songId, localAudioURL: localURL, tier: tier)
        case .lyra(let songId, let localURL, let serverOnline):
            if serverOnline, let remoteProvider {
                return AsyncStream { continuation in
                    let task = Task {
                        var remoteFailed = false
                        for await state in remoteProvider.prepare(songId: songId, localAudioURL: localURL, tier: tier) {
                            if case .failed = state, localURL != nil {
                                remoteFailed = true
                                break
                            }
                            continuation.yield(state)
                        }
                        // Seamless fallback to on-device Lyra-Sing-Lite if Lyra server disconnects while local cache exists.
                        if remoteFailed, let localURL {
                            for await localState in self.localProvider.prepare(songId: songId, localAudioURL: localURL, tier: tier) {
                                continuation.yield(localState)
                            }
                        }
                        continuation.finish()
                    }
                    continuation.onTermination = { _ in task.cancel() }
                }
            }
            return localProvider.prepare(songId: songId, localAudioURL: localURL, tier: tier)
        }
    }
}
