import Foundation
import Observation
import Security
import YoinShared

extension SubsonicClient: @retroactive @unchecked Sendable {}
extension SongInfo: @retroactive @unchecked Sendable {}
extension AlbumInfo: @retroactive @unchecked Sendable {}
extension ArtistInfo: @retroactive @unchecked Sendable {}
extension ArtistIndex: @retroactive @unchecked Sendable {}
extension SearchResult: @retroactive @unchecked Sendable {}
extension Lyrics: @retroactive @unchecked Sendable {}
extension LyricLine: @retroactive @unchecked Sendable {}
extension ServerInfo: @retroactive @unchecked Sendable {}
extension LyricsResult: @retroactive @unchecked Sendable {}

/// The Mac's accounts (iOS profiles, simplified): any number of Subsonic / Navidrome servers and Apple
/// Music, one in use. The list and its sign-ins live in one Keychain item; the client is YoinShared's
/// `SubsonicClient`.
@MainActor
@Observable
final class MacSession {
    struct Account: Codable, Equatable, Sendable {
        var baseUrl: String
        var username: String
        var password: String
    }

    /// One account in the list.
    struct Profile: Codable, Equatable, Identifiable, Sendable {
        enum Service: String, Codable, Sendable { case subsonic, appleMusic }
        var id: String
        var service: Service
        /// The Subsonic sign-in (nil for Apple Music).
        var account: Account?

        var title: String {
            switch service {
            case .appleMusic: "Apple Music"
            case .subsonic: account.map { "\($0.username) · \(MacSession.host($0.baseUrl))" } ?? "Server"
            }
        }
    }

    private struct Stored: Codable {
        var profiles: [Profile]
        var activeId: String?
    }

    enum State: Equatable {
        case signedOut
        case connecting
        case ready(server: String)
        case failed(String)
    }

    private(set) var profiles: [Profile] = []
    private(set) var activeId: String?
    private(set) var account: Account?
    private(set) var client: SubsonicClient?
    private(set) var state: State = .signedOut
    /// Apple Music in use instead of the Subsonic account (MusicKit; the system holds its sign-in).
    private(set) var apple: AppleMusicSource?

    var isSignedIn: Bool { client != nil || apple?.isAuthorized == true }
    var isAppleMusic: Bool { apple != nil }
    /// The account's key for per-account local state (server and user, never the password).
    var accountKey: String {
        if apple != nil { return AppleMusicMapping.provider }
        return account.map { "\($0.baseUrl)|\($0.username)" } ?? ""
    }

    private static let sourceKey = "activeSource"

    init() {
        if let stored = Keychain.loadProfiles() {
            profiles = stored.profiles
            activeId = stored.activeId
        } else {
            // First run with profiles: the single sign-in and the Apple Music choice become the list (the old
            // item stays, so an older build still finds it).
            if let saved = Keychain.load() {
                profiles.append(Profile(id: UUID().uuidString, service: .subsonic, account: saved))
            }
            if UserDefaults.standard.string(forKey: Self.sourceKey) == AppleMusicMapping.provider {
                profiles.append(Profile(id: UUID().uuidString, service: .appleMusic, account: nil))
                activeId = profiles.last?.id
            } else {
                activeId = profiles.first?.id
            }
            if !profiles.isEmpty { persist() }
        }
        if let p = profiles.first(where: { $0.id == activeId }) { activate(p) }
    }

    var activeProfile: Profile? { profiles.first { $0.id == activeId } }

    /// Makes [p] the account in use (the caller resets what was shown for the previous one).
    func switchTo(_ id: String) {
        guard let p = profiles.first(where: { $0.id == id }), id != activeId || !isSignedIn else { return }
        activeId = id
        persist()
        activate(p)
    }

    /// Takes [id] out of the list; the one in use leaves the app signed out.
    func remove(_ id: String) {
        // The pre-list single sign-in goes too when it is this account.
        if let a = profiles.first(where: { $0.id == id })?.account, Keychain.load() == a { Keychain.delete() }
        let wasActive = id == activeId
        profiles.removeAll { $0.id == id }
        if wasActive {
            activeId = nil
            deactivate()
        }
        persist()
    }

    private func activate(_ p: Profile) {
        deactivate()
        switch p.service {
        case .subsonic:
            if let a = p.account { restore(a) }
        case .appleMusic:
            let a = AppleMusicSource()
            // Access is the system's: without it the account waits for "Use Apple Music" again.
            guard a.isAuthorized else { state = .failed("Apple Music access is off"); return }
            apple = a
            state = .ready(server: "Apple Music")
        }
    }

    private func deactivate() {
        client?.close()
        client = nil
        account = nil
        apple = nil
        state = .signedOut
    }

    private func persist() {
        Keychain.saveProfiles(Stored(profiles: profiles, activeId: activeId))
    }

    nonisolated static func host(_ baseUrl: String) -> String { URL(string: baseUrl)?.host ?? baseUrl }

    /// Asks the system for Apple Music access (its own dialog, the listener answers it) and switches to
    /// it when allowed. The Subsonic sign-in stays in the Keychain.
    func useAppleMusic() async throws {
        let a = AppleMusicSource()
        await a.requestAuthorization()
        guard a.isAuthorized else {
            throw AppleMusicSource.RequestError(message: a.authorization == .denied
                ? "Apple Music access is off for Yoin in System Settings › Privacy & Security › Media & Apple Music"
                : "Apple Music isn't available")
        }
        deactivate()
        apple = a
        state = .ready(server: "Apple Music")
        let p = profiles.first { $0.service == .appleMusic }
            ?? Profile(id: UUID().uuidString, service: .appleMusic, account: nil)
        if !profiles.contains(where: { $0.id == p.id }) { profiles.append(p) }
        activeId = p.id
        persist()
    }

    /// Asks the server once; on success the account is saved and becomes the one in use.
    func signIn(url: String, username: String, password: String) async throws {
        let account = Account(baseUrl: Self.normalized(url), username: username.trimmingCharacters(in: .whitespaces),
                              password: password)
        let candidate = SubsonicClient(baseUrl: account.baseUrl, username: account.username, password: account.password)
        do {
            let info = try await candidate.ping()
            client?.close()
            deactivate()
            client = candidate
            self.account = account
            state = .ready(server: Self.serverName(info))
            // The same server and user again updates that entry (a new password), else it's a new account.
            if let i = profiles.firstIndex(where: { $0.account?.baseUrl == account.baseUrl
                && $0.account?.username == account.username }) {
                profiles[i].account = account
                activeId = profiles[i].id
            } else {
                let p = Profile(id: UUID().uuidString, service: .subsonic, account: account)
                profiles.append(p)
                activeId = p.id
            }
            persist()
        } catch {
            candidate.close()
            throw error
        }
    }

    /// Signs out of the account in use: it leaves the list (Apple Music: the system keeps its access).
    func signOut() {
        if let id = activeId { remove(id) } else { deactivate() }
    }

    private func restore(_ account: Account) {
        self.account = account
        let c = SubsonicClient(baseUrl: account.baseUrl, username: account.username, password: account.password)
        client = c
        state = .connecting
        Task {
            do {
                let info = try await c.ping()
                guard c === client else { return }
                state = .ready(server: Self.serverName(info))
            } catch {
                guard c === client else { return }
                state = .failed(Self.message(for: error))
            }
        }
    }

    private static func serverName(_ info: ServerInfo) -> String {
        [info.type?.capitalized, info.serverVersion].compactMap { $0 }.joined(separator: " ")
    }

    /// "music.example.com" → "https://music.example.com"; trailing slash off.
    static func normalized(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "https://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    static func message(for error: Error) -> String {
        if let k = (error as NSError).kotlinException as? SubsonicException {
            if k.isAuthFailure { return "Wrong username or password" }
            return SubsonicClient.companion.redactCredentials(text: k.message ?? "Server error")
        }
        return SubsonicClient.companion.redactCredentials(text: error.localizedDescription)
    }

    /// One generic-password item for the account (this Mac only).
    private enum Keychain {
        nonisolated(unsafe) static let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                           kSecAttrService as String: "com.gpo.yoin.mac.subsonic",
                                           kSecAttrAccount as String: "default"]

        static func load() -> Account? {
            var q = query
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: CFTypeRef?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
            return try? JSONDecoder().decode(Account.self, from: data)
        }

        static func save(_ account: Account) {
            guard let data = try? JSONEncoder().encode(account) else { return }
            let update: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
            if SecItemUpdate(query as CFDictionary, update as CFDictionary) == errSecItemNotFound {
                SecItemAdd(query.merging(update) { $1 } as CFDictionary, nil)
            }
        }

        static func delete() { SecItemDelete(query as CFDictionary) }

        /// The account list: its own item, so the single-account one above stays readable.
        nonisolated(unsafe) static let profilesQuery: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                                   kSecAttrService as String: "com.gpo.yoin.mac.subsonic",
                                                   kSecAttrAccount as String: "profiles"]

        static func loadProfiles() -> Stored? {
            var q = profilesQuery
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: CFTypeRef?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
            return try? JSONDecoder().decode(Stored.self, from: data)
        }

        static func saveProfiles(_ stored: Stored) {
            guard let data = try? JSONEncoder().encode(stored) else { return }
            let update: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
            if SecItemUpdate(profilesQuery as CFDictionary, update as CFDictionary) == errSecItemNotFound {
                SecItemAdd(profilesQuery.merging(update) { $1 } as CFDictionary, nil)
            }
        }
    }
}
