import Foundation
import Security
import YoinShared

/// The signed-in Subsonic / Navidrome server: credentials in the Keychain,
/// one shared `SubsonicClient` (Kotlin, YoinShared) for every screen.
///
/// Android keeps profiles in Room with encrypted credential blobs; iOS has
/// one server for now and stores it as a single Keychain item
/// (kSecClassGenericPassword, this device only).
@MainActor
@Observable
final class ServerSession {
    struct Account: Codable, Equatable {
        var baseUrl: String
        var username: String
        var password: String
    }

    enum State: Equatable {
        /// No server configured: the app shows the mock library.
        case signedOut
        case connecting
        case ready(ServerName)
        case failed(String)
    }

    struct ServerName: Equatable {
        var type: String?
        var version: String?
    }

    private(set) var account: Account?
    private(set) var state: State = .signedOut
    private(set) var client: SubsonicClient?

    var isSignedIn: Bool { client != nil }

    init() {
        if let saved = Keychain.load() {
            account = saved
            client = Self.makeClient(saved)
            state = .connecting
            Task { await verify() }
        }
    }

    /// Ping first; only a server that answers is saved.
    func signIn(baseUrl: String, username: String, password: String) async -> Bool {
        let account = Account(baseUrl: Self.normalized(baseUrl), username: username, password: password)
        let candidate = Self.makeClient(account)
        state = .connecting
        do {
            let info = try await candidate.ping()
            client?.close()
            client = candidate
            self.account = account
            Keychain.save(account)
            state = .ready(ServerName(type: info.type, version: info.serverVersion))
            return true
        } catch {
            candidate.close()
            state = .failed(Self.message(for: error))
            return false
        }
    }

    func signOut() {
        client?.close()
        client = nil
        account = nil
        Keychain.delete()
        state = .signedOut
    }

    private func verify() async {
        guard let client else { return }
        do {
            let info = try await client.ping()
            state = .ready(ServerName(type: info.type, version: info.serverVersion))
        } catch {
            // Keep the client: an offline launch should still play cached pages.
            state = .failed(Self.message(for: error))
        }
    }

    private static func makeClient(_ a: Account) -> SubsonicClient {
        SubsonicClient(baseUrl: a.baseUrl, username: a.username, password: a.password)
    }

    /// "music.example.com" → "https://music.example.com"; trailing slash off.
    static func normalized(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "https://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    /// A short label for a failed call (Kotlin's SubsonicException when present).
    static func message(for error: Error) -> String {
        if let k = (error as NSError).kotlinException as? SubsonicException {
            if k.isAuthFailure { return "Wrong username or password" }
            return k.message ?? "Server error"
        }
        return error.localizedDescription
    }
}

private enum Keychain {
    private static let service = "com.gpo.yoin.subsonic"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: "default"]
    }

    static func load() -> ServerSession.Account? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(ServerSession.Account.self, from: data)
    }

    static func save(_ account: ServerSession.Account) {
        guard let data = try? JSONEncoder().encode(account) else { return }
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    static func delete() {
        SecItemDelete(query as CFDictionary)
    }
}
