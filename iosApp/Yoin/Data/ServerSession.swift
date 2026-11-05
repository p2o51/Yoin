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

    private(set) var account: Account? {
        didSet { AccountScope.shared.subsonic = account.map(AccountScope.subsonicScope) }
    }
    private(set) var state: State = .signedOut
    private(set) var client: SubsonicClient?
    /// The server lists OpenSubsonic `transcodeOffset`: `stream` takes `timeOffset`, so a transcode can
    /// reopen mid-song (Android `SubsonicServerTraits.timeOffset`). False until the server says so.
    private(set) var supportsTimeOffset = false

    var isSignedIn: Bool { client != nil }

    /// A different server or user is about to replace the signed-in one (not a password change):
    /// the old account's playback, queue and callbacks must end before its client goes (AppModel).
    @ObservationIgnored var onAccountReplaced: (() -> Void)?

    /// Bumps on every sign-in attempt and sign-out: an answer from an older attempt (a ping still in
    /// flight when the user signed out or tried again) never installs its client, account or Keychain item.
    @ObservationIgnored private var authGeneration = 0

    init() {
        if let saved = Keychain.load() {
            account = saved
            AccountScope.shared.subsonic = AccountScope.subsonicScope(saved)
            client = Self.makeClient(saved)
            state = .connecting
            Task { await verify() }
        }
    }

    /// Ping first; only a server that answers is saved.
    func signIn(baseUrl: String, username: String, password: String) async -> Bool {
        authGeneration += 1
        let gen = authGeneration
        let account = Account(baseUrl: Self.normalized(baseUrl), username: username, password: password)
        let candidate = Self.makeClient(account)
        state = .connecting
        do {
            let info = try await candidate.ping()
            guard gen == authGeneration else {
                // Signed out, or another attempt started, while this one was asking.
                candidate.close()
                return false
            }
            let status = Keychain.save(account)
            // An unsigned simulator build has no Keychain: the sign-in lasts this run only, as before.
            guard status == errSecSuccess || status == errSecMissingEntitlement else {
                candidate.close()
                // The previous sign-in (client and Keychain item) stays as it was.
                state = .failed("Couldn't save the sign-in (Keychain error \(status))")
                return false
            }
            if let old = self.account, Self.identity(old) != Self.identity(account) { onAccountReplaced?() }
            client?.close()
            client = candidate
            self.account = account
            supportsTimeOffset = false
            state = .ready(ServerName(type: info.type, version: info.serverVersion))
            Task { await loadTraits(candidate, gen: gen, openSubsonic: info.openSubsonic) }
            return true
        } catch {
            candidate.close()
            guard gen == authGeneration else { return false }
            state = .failed(Self.message(for: error))
            return false
        }
    }

    func signOut() {
        authGeneration += 1
        client?.close()
        client = nil
        account = nil
        supportsTimeOffset = false
        Keychain.delete()
        state = .signedOut
    }

    private func verify() async {
        guard let client else { return }
        let gen = authGeneration
        do {
            let info = try await client.ping()
            guard gen == authGeneration, client === self.client else { return }
            state = .ready(ServerName(type: info.type, version: info.serverVersion))
            Task { await loadTraits(client, gen: gen, openSubsonic: info.openSubsonic) }
        } catch {
            guard gen == authGeneration, client === self.client else { return }
            // Keep the client: an offline launch should still play cached pages.
            state = .failed(Self.message(for: error))
        }
    }

    /// OpenSubsonic extensions, once per sign-in (a plain Subsonic server has none).
    private func loadTraits(_ client: SubsonicClient, gen: Int, openSubsonic: Bool) async {
        guard openSubsonic else { return }
        let names = (try? await client.getOpenSubsonicExtensions()) ?? []
        guard gen == authGeneration, client === self.client else { return }
        supportsTimeOffset = names.contains("transcodeOffset")
    }

    /// Server + user: the account a client signs in as (a new password is the same account).
    private static func identity(_ a: Account) -> String { AccountScope.subsonicScope(a) }

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
            return SubsonicClient.companion.redactCredentials(text: k.message ?? "Server error")
        }
        return SubsonicClient.companion.redactCredentials(text: error.localizedDescription)
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

    /// Updates the item in place (Add only when there is none), so a failure never loses the old one.
    static func save(_ account: ServerSession.Account) -> OSStatus {
        guard let data = try? JSONEncoder().encode(account) else { return errSecParam }
        return KeychainItem.upsert(query: query, data: data)
    }

    static func delete() {
        SecItemDelete(query as CFDictionary)
    }
}

/// Keychain writes that keep the previous item when the new one can't be stored.
enum KeychainItem {
    static func upsert(query: [String: Any], data: Data) -> OSStatus {
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        guard status == errSecItemNotFound else { return status }
        var add = query
        add.merge(update) { $1 }
        return SecItemAdd(add as CFDictionary, nil)
    }
}

/// Which account local rows (notes, ratings, album memories) belong to, per provider: Android keeps them
/// per profile; iOS has one Subsonic server at a time, so a Subsonic row carries the server + user it was
/// written under ("baseUrl|username", the same scope as the play history) and is shown, synced and edited
/// only while that account is signed in (Codex C03). Apple Music has one account per device: no scope.
@MainActor
@Observable
final class AccountScope {
    static let shared = AccountScope()

    /// The signed-in Subsonic account's scope; nil signed out.
    var subsonic: String? {
        didSet {
            if let subsonic { adoptLegacyIfNeeded(subsonic) }
            // That account's downloads run (another account's wait).
            if subsonic != oldValue { OfflineDownloads.shared.accountChanged() }
        }
    }

    static func subsonicScope(_ a: ServerSession.Account) -> String { "\(a.baseUrl)|\(a.username)" }

    /// The scope a row of [provider] is written under and read back in.
    func scope(for provider: String) -> String? { provider == Self.subsonicProvider ? subsonic : nil }

    func isVisible(provider: String, scope: String?) -> Bool { scope == self.scope(for: provider) }

    static let subsonicProvider = "subsonic"

    /// Rows saved before they carried an account go to the first Subsonic account seen after the update
    /// (the one configured then; once per install, never on a later account change).
    @ObservationIgnored var adoptsLegacyRows = true
    static let legacyAdoptedKey = "accountScopeLegacyAdopted"

    private func adoptLegacyIfNeeded(_ scope: String) {
        guard adoptsLegacyRows, !UserDefaults.standard.bool(forKey: Self.legacyAdoptedKey) else { return }
        UserDefaults.standard.set(true, forKey: Self.legacyAdoptedKey)
        NotesStore.shared.adoptLegacy(provider: Self.subsonicProvider, scope: scope)
        LocalRatings.shared.adoptLegacy(provider: Self.subsonicProvider, scope: scope)
        AlbumMemoryStore.shared.adoptLegacy(provider: Self.subsonicProvider, scope: scope)
    }
}
