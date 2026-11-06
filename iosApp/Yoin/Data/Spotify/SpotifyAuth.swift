import AuthenticationServices
import CryptoKit
import Foundation
import Security
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Spotify's constants (Android SpotifyAuthConfig). The Client ID is not one of them: a user sets it
/// in Settings › Spotify (synced as `spotify_client_id`); a dev build may be seeded with one from
/// Config/Spotify.local.xcconfig, kept out of git as Android keeps it in local.properties.
enum SpotifyConfig {
    static let redirectURI = "yoin://auth/spotify/callback"
    static let appRemoteRedirectURI = "yoin://auth/spotify/app-remote"
    static let callbackScheme = "yoin"
    static let authorizeURL = URL(string: "https://accounts.spotify.com/authorize")!
    static let tokenURL = URL(string: "https://accounts.spotify.com/api/token")!

    /// Android SCOPES: library and playlist reads and writes, follows, App Remote control and
    /// streaming, playback state and control (context playback, Devices) and recently played.
    static let scopes = [
        "user-read-private", "user-library-read", "user-library-modify",
        "playlist-read-private", "playlist-read-collaborative",
        "user-follow-read", "user-follow-modify",
        "app-remote-control", "streaming",
        "playlist-modify-private", "playlist-modify-public",
        "user-modify-playback-state", "user-read-playback-state", "user-read-recently-played",
    ]
    /// Android REQUIRED_SCOPES: a token missing any of these must sign in again.
    static let requiredScopes: Set<String> = [
        "user-read-private", "app-remote-control", "streaming",
        "playlist-modify-private", "playlist-modify-public", "user-modify-playback-state",
        // Where Spotify plays (GET me/player): starts on another device and Yoin's progress read it.
        "user-read-playback-state",
    ]

    static let clientIdKey = "spotifyClientId"

    /// The Client ID in use: the one set in Settings, else the dev build's seed; nil = none yet.
    static var clientId: String? {
        let set = UserDefaults.standard.string(forKey: clientIdKey)?.trimmingCharacters(in: .whitespaces) ?? ""
        if !set.isEmpty { return set }
        let seed = (Bundle.main.object(forInfoDictionaryKey: "YoinSpotifyClientID") as? String)?
            .trimmingCharacters(in: .whitespaces) ?? ""
        return seed.isEmpty ? nil : seed
    }

    static func setClientId(_ id: String?) {
        let v = id?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if v.isEmpty { UserDefaults.standard.removeObject(forKey: clientIdKey) } else { UserDefaults.standard.set(v, forKey: clientIdKey) }
    }
}

/// One Spotify sign-in: the tokens and who they belong to.
struct SpotifyTokens: Codable, Equatable, Sendable {
    var accessToken: String
    var refreshToken: String
    /// Epoch seconds.
    var expiresAt: Double
    var scope: String
    var clientId: String
    var userId: String
    var displayName: String?

    var scopes: Set<String> { Set(scope.split(separator: " ").map(String.init)) }
    /// Signed in before a required scope was added: it has to sign in again (Android NeedsReconnect).
    var missingRequiredScopes: Bool { !SpotifyConfig.requiredScopes.isSubset(of: scopes) }
    var expiresSoon: Bool { Date().timeIntervalSince1970 > expiresAt - 60 }
}

enum SpotifyAuthError: LocalizedError, Equatable {
    case noClientId
    case cancelled
    /// The refresh token was revoked (invalid_grant): sign in again.
    case revoked
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .noClientId: "Add your Spotify Client ID in Settings first"
        case .cancelled: "Sign-in cancelled"
        case .revoked: "Spotify signed you out. Sign in again"
        case .failed(let m): m
        }
    }
}

/// Each Spotify account's tokens, one Keychain item apiece (this device only).
enum SpotifyTokenStore {
    static let service = "com.gpo.yoin.spotify"

    private static func query(_ profileId: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: profileId]
    }

    static func load(_ profileId: String) -> SpotifyTokens? {
        var q = query(profileId)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(SpotifyTokens.self, from: data)
    }

    @discardableResult
    static func save(_ tokens: SpotifyTokens, for profileId: String) -> OSStatus {
        guard let data = try? JSONEncoder().encode(tokens) else { return errSecParam }
        let update = [kSecValueData as String: data]
        var status = SecItemUpdate(query(profileId) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(profileId)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(add as CFDictionary, nil)
        }
        return status
    }

    static func delete(_ profileId: String) {
        SecItemDelete(query(profileId) as CFDictionary)
    }
}

/// Spotify's OAuth (Android SpotifyAuthService + SpotifyOAuthActivity): Authorization Code with PKCE
/// in an ASWebAuthenticationSession (no client secret), the token exchange and refresh, and /me.
@MainActor
enum SpotifyAuth {
    /// Signs in: the Spotify consent page, then the code exchanged for tokens.
    static func signIn() async throws -> SpotifyTokens {
        guard let clientId = SpotifyConfig.clientId else { throw SpotifyAuthError.noClientId }
        let verifier = randomString(64)
        let state = randomString(16)
        var c = URLComponents(url: SpotifyConfig.authorizeURL, resolvingAgainstBaseURL: false)!
        c.queryItems = [
            .init(name: "client_id", value: clientId),
            .init(name: "response_type", value: "code"),
            .init(name: "redirect_uri", value: SpotifyConfig.redirectURI),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "code_challenge", value: challenge(verifier)),
            .init(name: "state", value: state),
            .init(name: "scope", value: SpotifyConfig.scopes.joined(separator: " ")),
        ]
        let callback = try await webAuth(c.url!)
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let error = items.first(where: { $0.name == "error" })?.value {
            throw error == "access_denied" ? SpotifyAuthError.cancelled : SpotifyAuthError.failed(error)
        }
        guard items.first(where: { $0.name == "state" })?.value == state,
              let code = items.first(where: { $0.name == "code" })?.value
        else { throw SpotifyAuthError.failed("Spotify didn't return a sign-in code") }
        let token = try await postToken([
            "grant_type": "authorization_code", "code": code, "redirect_uri": SpotifyConfig.redirectURI,
            "client_id": clientId, "code_verifier": verifier,
        ])
        guard let refresh = token.refresh_token else { throw SpotifyAuthError.failed("Spotify didn't return a refresh token") }
        let me = try await fetchMe(token.access_token)
        return SpotifyTokens(accessToken: token.access_token, refreshToken: refresh,
                             expiresAt: Date().timeIntervalSince1970 + Double(token.expires_in),
                             scope: token.scope ?? SpotifyConfig.scopes.joined(separator: " "), clientId: clientId,
                             userId: me.id, displayName: me.display_name)
    }

    /// A fresh access token for [tokens] (Spotify may rotate the refresh token too).
    nonisolated static func refresh(_ tokens: SpotifyTokens) async throws -> SpotifyTokens {
        let token = try await postToken(["grant_type": "refresh_token", "refresh_token": tokens.refreshToken,
                                         "client_id": tokens.clientId])
        var t = tokens
        t.accessToken = token.access_token
        t.expiresAt = Date().timeIntervalSince1970 + Double(token.expires_in)
        if let r = token.refresh_token { t.refreshToken = r }
        if let s = token.scope { t.scope = s }
        return t
    }

    struct Me: Decodable { let id: String; let display_name: String?; let product: String? }

    nonisolated static func fetchMe(_ accessToken: String) async throws -> Me {
        var r = URLRequest(url: URL(string: "https://api.spotify.com/v1/me")!)
        r.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: r)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw SpotifyAuthError.failed("Spotify couldn't read your account (\((response as? HTTPURLResponse)?.statusCode ?? 0))")
        }
        return try JSONDecoder().decode(Me.self, from: data)
    }

    // MARK: Plumbing

    private struct TokenResponse: Decodable {
        let access_token: String
        let refresh_token: String?
        let expires_in: Int
        let scope: String?
    }

    private struct TokenError: Decodable { let error: String; let error_description: String? }

    nonisolated private static func postToken(_ form: [String: String]) async throws -> TokenResponse {
        var r = URLRequest(url: SpotifyConfig.tokenURL)
        r.httpMethod = "POST"
        r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var c = URLComponents()
        c.queryItems = form.map { URLQueryItem(name: $0.key, value: $0.value) }
        r.httpBody = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B").data(using: .utf8)
        let (data, response) = try await URLSession.shared.data(for: r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            let e = try? JSONDecoder().decode(TokenError.self, from: data)
            if e?.error == "invalid_grant" { throw SpotifyAuthError.revoked }
            throw SpotifyAuthError.failed(e?.error_description ?? e?.error ?? "Spotify sign-in failed (\(status))")
        }
        return try JSONDecoder().decode(TokenResponse.self, from: data)
    }

    private static func webAuth(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(url: url, callback: .customScheme(SpotifyConfig.callbackScheme)) { @Sendable callback, error in
                // macOS answers on a background queue (iOS on main): hop, never assume.
                Task { @MainActor in current = nil }
                if let callback { cont.resume(returning: callback); return }
                if let e = error as? ASWebAuthenticationSessionError, e.code == .canceledLogin {
                    cont.resume(throwing: SpotifyAuthError.cancelled)
                } else {
                    cont.resume(throwing: SpotifyAuthError.failed(error?.localizedDescription ?? "Sign-in failed"))
                }
            }
            session.presentationContextProvider = AuthAnchor.shared
            session.prefersEphemeralWebBrowserSession = false
            // Kept until it answers: nothing else holds the session once this returns.
            current = session
            if !session.start() {
                current = nil
                cont.resume(throwing: SpotifyAuthError.failed("Couldn't open Spotify's sign-in"))
            }
        }
    }

    private static var current: ASWebAuthenticationSession?

    private final class AuthAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
        static let shared = AuthAnchor()
        func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
            MainActor.assumeIsolated {
                #if canImport(UIKit)
                let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
                #else
                return NSApp.keyWindow ?? NSApp.mainWindow ?? ASPresentationAnchor()
                #endif
            }
        }
    }

    private static func randomString(_ n: Int) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        var bytes = [UInt8](repeating: 0, count: n)
        _ = SecRandomCopyBytes(kSecRandomDefault, n, &bytes)
        return String(bytes.map { alphabet[Int($0) % alphabet.count] })
    }

    /// S256: base64url(sha256(verifier)), no padding.
    static func challenge(_ verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/// One account's access token, refreshed when it is about to expire (one refresh at a time) and
/// written back to the Keychain; a revoked sign-in reports itself so the account asks to reconnect.
actor SpotifyTokenProvider {
    let profileId: String
    private var tokens: SpotifyTokens
    private var refreshing: Task<SpotifyTokens, Error>?
    /// Called when the refresh token was revoked.
    private let onRevoked: @Sendable () -> Void
    /// Called with the tokens after every refresh (saved to the Keychain already).
    private let onRefreshed: @Sendable (SpotifyTokens) -> Void

    init(profileId: String, tokens: SpotifyTokens, onRefreshed: @escaping @Sendable (SpotifyTokens) -> Void = { _ in },
         onRevoked: @escaping @Sendable () -> Void) {
        self.profileId = profileId
        self.tokens = tokens
        self.onRefreshed = onRefreshed
        self.onRevoked = onRevoked
    }

    var current: SpotifyTokens { tokens }

    /// A token valid for at least a minute; [force] refreshes even so (after a 401).
    func accessToken(force: Bool = false) async throws -> String {
        if !force && !tokens.expiresSoon { return tokens.accessToken }
        if let refreshing { return try await refreshing.value.accessToken }
        let old = tokens
        let task = Task { try await SpotifyAuth.refresh(old) }
        refreshing = task
        defer { refreshing = nil }
        do {
            let fresh = try await task.value
            tokens = fresh
            let id = profileId
            // Only over the sign-in this refresh came from: a removed account isn't brought back, and a newer
            // sign-in of the same account isn't overwritten.
            let current = await MainActor.run { () -> Bool in
                guard SpotifyTokenStore.load(id)?.refreshToken == old.refreshToken else { return false }
                _ = SpotifyTokenStore.save(fresh, for: id)
                return true
            }
            if current { onRefreshed(fresh) }
            return fresh.accessToken
        } catch SpotifyAuthError.revoked {
            onRevoked()
            throw SpotifyAuthError.revoked
        }
    }
}
