import Foundation
import Observation
import Security

/// Where the NeoDB token lives. The app uses the Keychain; tests use memory.
protocol NeoDBTokenStoring: Sendable {
    func load() -> String?
    /// The Keychain's status.
    func save(_ token: String) -> OSStatus
    func delete()
}

/// The NeoDB access token as one Keychain item, this device only (Android keeps it encrypted in
/// `noBackupFilesDir`, outside backups, for the same reason: a restored phone signs in again).
struct NeoDBKeychain: NeoDBTokenStoring {
    var service = "com.gpo.yoin.neodb"
    var account = "accessToken"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    func load() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8).flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Updated in place (added only when there is none), so a failure keeps the previous token.
    func save(_ token: String) -> OSStatus {
        KeychainItem.upsert(query: query, data: Data(token.utf8))
    }

    func delete() {
        SecItemDelete(query as CFDictionary)
    }
}

/// The NeoDB sign-in: the instance (UserDefaults, Android's `neodb_config` row) and the token (Keychain).
/// Signing out drops the token and keeps the instance, so the next sign-in doesn't ask for it again.
@MainActor
@Observable
final class NeoDBAccount {
    static let shared = NeoDBAccount()

    static let defaultInstance = "https://neodb.social"

    private(set) var instance: String
    private(set) var token: String

    /// Signed in: an instance and a token.
    var isSignedIn: Bool { !instance.isEmpty && !token.isEmpty }

    /// (instance, token) for requests; nil signed out.
    var session: (instance: String, token: String)? {
        isSignedIn ? (instance, token) : nil
    }

    @ObservationIgnored private let tokens: NeoDBTokenStoring
    @ObservationIgnored private let defaults: UserDefaults
    private static let instanceKey = "neoDBInstance"

    init(tokens: NeoDBTokenStoring = NeoDBKeychain(), defaults: UserDefaults = .standard) {
        self.tokens = tokens
        self.defaults = defaults
        instance = defaults.string(forKey: Self.instanceKey).flatMap { $0.isEmpty ? nil : $0 } ?? Self.defaultInstance
        token = tokens.load() ?? ""
    }

    /// Android `saveNeoDbConfig`: the instance normalized, the token trimmed (empty signs out). False
    /// when the instance isn't a URL or the Keychain refused the token (the previous one stays). An
    /// unsigned simulator build has no Keychain: the token is kept for this run, as Gemini's key is.
    @discardableResult
    func save(instance raw: String, token rawToken: String) -> Bool {
        guard let normalized = Self.normalizeInstance(raw) else { return false }
        let t = rawToken.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty {
            setInstance(normalized)
            signOut()
            return true
        }
        // The token first: a refused one leaves the instance and token as they were, never the old
        // token paired with the new instance (Codex, 2026-11-06).
        let status = tokens.save(t)
        guard status == errSecSuccess || status == errSecMissingEntitlement else { return false }
        setInstance(normalized)
        token = t
        return true
    }

    /// Remembers the instance about to be signed in to (Android `openNeoDbSignIn`).
    func setInstance(_ normalized: String) {
        defaults.set(normalized, forKey: Self.instanceKey)
        instance = normalized
    }

    func signOut() {
        tokens.delete()
        token = ""
    }

    /// Android `normalizeNeoDbInstance`: trimmed, blank → neodb.social, `https://` added when no scheme
    /// is given, and only scheme://host[:port] kept. nil when there is no host.
    static func normalizeInstance(_ raw: String, blankIsDefault: Bool = true) -> String? {
        var trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        if trimmed.isEmpty {
            guard blankIsDefault else { return nil }
            trimmed = defaultInstance
        }
        let candidate = trimmed.contains("://") ? trimmed : "https://" + trimmed
        guard let c = URLComponents(string: candidate), let scheme = c.scheme, !scheme.isEmpty,
              let host = c.host, !host.isEmpty else { return nil }
        let port = c.port.map { ":\($0)" } ?? ""
        let h = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        return "\(scheme)://\(h)\(port)"
    }
}

/// A token kept in memory (tests, previews).
final class NeoDBMemoryTokens: NeoDBTokenStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?

    init(_ value: String? = nil) { self.value = value }

    func load() -> String? { lock.withLock { value } }
    func save(_ token: String) -> OSStatus { lock.withLock { value = token }; return errSecSuccess }
    func delete() { lock.withLock { value = nil } }
}
