import AuthenticationServices
import Foundation
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Shows NeoDB's authorize page and hands back the redirect. The app uses ASWebAuthenticationSession;
/// tests use a fake.
@MainActor
protocol NeoDBWebAuthenticating {
    /// The callback URL; throws `NeoDBOAuth.Cancelled` when the person closes the page.
    func authenticate(url: URL, callbackScheme: String) async throws -> URL
}

/// NeoDB sign-in — Android `NeoDBOAuthActivity` + `NeoDBOAuthContract`, same steps:
///  1. register an OAuth app with the chosen NeoDB (Mastodon-compatible) instance,
///  2. open `/oauth/authorize` (here in ASWebAuthenticationSession; Android uses a Custom Tab),
///  3. take the `yoin://auth/neodb/callback` redirect and check its state nonce,
///  4. exchange the code for a token.
/// ASWebAuthenticationSession catches the callback itself, so the `yoin` scheme needs no Info.plist
/// entry. Like Android, no PKCE: the client secret from step 1 authenticates the exchange.
@MainActor
final class NeoDBOAuth {
    enum Result: Equatable {
        case success(instance: String, accessToken: String)
        case failure(String)
        case cancelled
    }

    struct Cancelled: Error {}

    static let callbackScheme = "yoin"
    static let callbackURI = "yoin://auth/neodb/callback"
    static let clientName = "Yoin"
    static let website = "https://github.com/p2o51/Yoin"
    static let scope = "read write"

    private let api: NeoDBAPI
    private let authenticator: NeoDBWebAuthenticating
    private let makeState: () -> String

    init(api: NeoDBAPI = NeoDBAPI(), authenticator: NeoDBWebAuthenticating = SystemWebAuthenticator(),
         makeState: @escaping () -> String = { UUID().uuidString }) {
        self.api = api
        self.authenticator = authenticator
        self.makeState = makeState
    }

    func signIn(instance raw: String) async -> Result {
        guard let instance = NeoDBAccount.normalizeInstance(raw, blankIsDefault: false) else {
            return .failure("Invalid NeoDB URL. Include a valid host.")
        }
        let registration: NeoDBClientRegistration
        do {
            registration = try await api.registerOAuthApp(instance: instance, redirectURI: Self.callbackURI,
                                                          clientName: Self.clientName, website: Self.website)
        } catch {
            return .failure(Self.message(error, fallback: "NeoDB sign-in failed"))
        }
        let state = makeState()
        guard let authURL = Self.authorizationURL(instance: instance, clientId: registration.clientId, state: state) else {
            return .failure("Invalid NeoDB URL. Include a valid host.")
        }
        let callback: URL
        do {
            callback = try await authenticator.authenticate(url: authURL, callbackScheme: Self.callbackScheme)
        } catch is Cancelled {
            return .cancelled
        } catch {
            return .failure(Self.message(error, fallback: "NeoDB sign-in failed"))
        }
        let code: String
        switch Self.parseCallback(callback, expectedState: state) {
        case .failure(let message): return .failure(message)
        case .success(let c): code = c
        }
        do {
            let token = try await api.exchangeOAuthCode(instance: instance, clientId: registration.clientId,
                                                        clientSecret: registration.clientSecret, code: code,
                                                        redirectURI: Self.callbackURI)
            guard !token.accessToken.isEmpty else { return .failure("NeoDB returned incomplete credentials") }
            return .success(instance: instance, accessToken: token.accessToken)
        } catch {
            return .failure(Self.message(error, fallback: "NeoDB token exchange failed"))
        }
    }

    /// `{instance}/oauth/authorize?response_type=code&client_id=…&redirect_uri=…&scope=read write&state=…`.
    static func authorizationURL(instance: String, clientId: String, state: String) -> URL? {
        guard var c = URLComponents(string: instance + "/oauth/authorize") else { return nil }
        c.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "redirect_uri", value: callbackURI),
            URLQueryItem(name: "scope", value: scope),
            URLQueryItem(name: "state", value: state),
        ]
        return c.url
    }

    enum Callback: Equatable {
        case success(String)
        case failure(String)
    }

    /// The code from NeoDB's redirect, or why there is none (an `error`, a wrong place, a missing code
    /// or a state that isn't the one sent).
    static func parseCallback(_ url: URL, expectedState: String) -> Callback {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), c.scheme == callbackScheme,
              c.host == "auth", c.path.hasPrefix("/neodb/callback") else {
            return .failure("NeoDB redirect missing required parameters")
        }
        func value(_ name: String) -> String? { c.queryItems?.first { $0.name == name }?.value }
        if let error = value("error") { return .failure("NeoDB auth error: \(error)") }
        guard let code = value("code"), !code.isEmpty, let state = value("state"), !state.isEmpty else {
            return .failure("NeoDB redirect missing required parameters")
        }
        guard state == expectedState else { return .failure("NeoDB state nonce mismatch") }
        return .success(code)
    }

    private static func message(_ error: Error, fallback: String) -> String {
        if let e = error as? NeoDBError, !e.message.isEmpty { return e.message }
        let m = error.localizedDescription
        return m.isEmpty ? fallback : m
    }
}

/// ASWebAuthenticationSession over the app's key window; a shared browser session, so a NeoDB login
/// already in Safari carries over.
@MainActor
final class SystemWebAuthenticator: NSObject, NeoDBWebAuthenticating {
    private var session: ASWebAuthenticationSession?

    func authenticate(url: URL, callbackScheme: String) async throws -> URL {
        defer { session = nil }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            let s = ASWebAuthenticationSession(url: url, callback: .customScheme(callbackScheme)) { @Sendable callback, error in
                // @Sendable: macOS answers on a background queue, and a main-actor closure would trap there.
                if let callback {
                    continuation.resume(returning: callback)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(throwing: NeoDBOAuth.Cancelled())
                } else {
                    continuation.resume(throwing: error ?? NeoDBOAuth.Cancelled())
                }
            }
            s.presentationContextProvider = self
            session = s
            if !s.start() {
                continuation.resume(throwing: NeoDBError(code: 0, message: "NeoDB sign-in failed"))
            }
        }
    }
}

extension SystemWebAuthenticator: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        #if os(macOS)
        // Sign-in starts from a click in Settings, so a key window is there.
        return NSApp.keyWindow ?? NSApp.windows.first ?? NSWindow()
        #else
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap(\.windows)
        if let key = windows.first(where: \.isKeyWindow) ?? windows.first { return key }
        // Sign-in starts from a tap in Settings, so a scene is always there.
        guard let scene = scenes.first else { preconditionFailure("NeoDB sign-in without a window scene") }
        return UIWindow(windowScene: scene)
        #endif
    }
}
