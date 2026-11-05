import Foundation
import Security
import YoinShared

/// The Subsonic / Navidrome server in use: one shared `SubsonicClient`
/// (Kotlin, YoinShared) for every screen.
///
/// Which server that is belongs to the account list (`ProfileManager`, Android
/// ProfileManager): each Subsonic account keeps its sign-in in its own
/// Keychain item (`ProfileCredentials`), and the account in use is handed here
/// on launch (`restore`, no wait for the server) or on a switch (`install`,
/// after the server answered `probe`).
@MainActor
@Observable
final class ServerSession {
    struct Account: Codable, Equatable, Sendable {
        var baseUrl: String
        var username: String
        var password: String
    }

    enum State: Equatable {
        /// No server in use: the app shows the active account's other source, or the mock library.
        case signedOut
        case connecting
        case ready(ServerName)
        case failed(String)
    }

    struct ServerName: Equatable {
        var type: String?
        var version: String?
    }

    /// A server that answered its ping, with the client that asked: installed on a switch, or closed.
    struct Probe {
        let client: SubsonicClient
        let server: ServerName
        let openSubsonic: Bool
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

    /// Bumps on every install, restore and sign-out: an answer for an older client (a ping still in
    /// flight when the account changed) never changes the state.
    @ObservationIgnored private var authGeneration = 0

    init() {}

    /// Asks [account]'s server once with a client of its own; nothing here changes until `install`.
    static func probe(_ account: Account) async throws -> Probe {
        let candidate = makeClient(account)
        do {
            let info = try await candidate.ping()
            return Probe(client: candidate, server: ServerName(type: info.type, version: info.serverVersion),
                         openSubsonic: info.openSubsonic)
        } catch {
            candidate.close()
            throw error
        }
    }

    /// The account in use becomes [account], on the client that just answered (a switch, a sign-in).
    func install(_ probe: Probe, account: Account) {
        authGeneration += 1
        let gen = authGeneration
        if client !== probe.client { client?.close() }
        client = probe.client
        self.account = account
        supportsTimeOffset = false
        state = .ready(probe.server)
        Task { await loadTraits(probe.client, gen: gen, openSubsonic: probe.openSubsonic) }
    }

    /// The account in use at launch (or after the one in use was removed): its client at once, the
    /// ping behind it. An offline launch keeps the client, so cached pages still play.
    func restore(_ account: Account) {
        authGeneration += 1
        client?.close()
        self.account = account
        client = Self.makeClient(account)
        supportsTimeOffset = false
        state = .connecting
        Task { await verify() }
    }

    /// No Subsonic server in use (another source, or no account). The account's sign-in stays saved.
    func signOut() {
        authGeneration += 1
        client?.close()
        client = nil
        account = nil
        supportsTimeOffset = false
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

/// Each Subsonic account's sign-in, one Keychain item apiece (kSecClassGenericPassword, this device
/// only, kSecAttrAccount = the profile id). The account from before the account list keeps the item
/// it always had (kSecAttrAccount "default"): moving it would be one more write that could fail.
protocol ProfileCredentialStore {
    func load(_ profileId: String) -> CredentialRead
    func save(_ account: ServerSession.Account, for profileId: String) -> OSStatus
    func delete(_ profileId: String)
}

enum CredentialRead: Equatable {
    case found(ServerSession.Account)
    case missing
    /// The Keychain couldn't be read just now (locked, no entitlement): not proof there is none.
    case unavailable(OSStatus)
}

struct KeychainCredentials: ProfileCredentialStore {
    static let service = "com.gpo.yoin.subsonic"
    /// The single-server build's item; the migrated account reads it in place.
    static let legacyAccount = "default"

    static func keychainAccount(_ profileId: String) -> String {
        profileId == ProfileStore.legacySubsonicId ? legacyAccount : profileId
    }

    private static func query(_ profileId: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: keychainAccount(profileId)]
    }

    func load(_ profileId: String) -> CredentialRead {
        var q = Self.query(profileId)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        switch status {
        case errSecSuccess:
            guard let data = out as? Data, let a = try? JSONDecoder().decode(ServerSession.Account.self, from: data)
            else { return .missing }
            return .found(a)
        case errSecItemNotFound: return .missing
        default: return .unavailable(status)
        }
    }

    /// Updates the item in place (Add only when there is none), so a failure never loses the old one.
    func save(_ account: ServerSession.Account, for profileId: String) -> OSStatus {
        guard let data = try? JSONEncoder().encode(account) else { return errSecParam }
        return KeychainItem.upsert(query: Self.query(profileId), data: data)
    }

    func delete(_ profileId: String) {
        SecItemDelete(Self.query(profileId) as CFDictionary)
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
/// per profile; iOS reads one Subsonic server at a time, so a Subsonic row carries the server + user it was
/// written under ("baseUrl|username", the same scope as the play history) and is shown and edited only
/// while that account is in use (Codex C03). Cloud sync reads another account's rows through `reading`.
/// Apple Music has one account per device: no scope.
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
    func scope(for provider: String) -> String? { provider == Self.subsonicProvider ? (pinned ?? subsonic) : nil }

    /// Set only inside `reading`: the Subsonic account the stores read and write for the moment.
    @ObservationIgnored private var pinned: String?

    /// Runs [body] (synchronously, on the main actor) as if [subsonicScope] were the account in use, without
    /// switching: cloud sync reads and writes each account's rows, not just the one in use. Nothing
    /// observes the change (no downloads, no legacy adoption).
    func reading<T>(asSubsonic subsonicScope: String?, _ body: () -> T) -> T {
        guard let subsonicScope else { return body() }
        let before = pinned
        pinned = subsonicScope
        defer { pinned = before }
        return body()
    }

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
