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

/// The one Subsonic / Navidrome account the Mac app uses (P0: no account list).
/// The sign-in lives in the Keychain; the client is YoinShared's `SubsonicClient`.
@MainActor
@Observable
final class MacSession {
    struct Account: Codable, Equatable, Sendable {
        var baseUrl: String
        var username: String
        var password: String
    }

    enum State: Equatable {
        case signedOut
        case connecting
        case ready(server: String)
        case failed(String)
    }

    private(set) var account: Account?
    private(set) var client: SubsonicClient?
    private(set) var state: State = .signedOut

    var isSignedIn: Bool { client != nil }

    init() {
        if let saved = Keychain.load() { restore(saved) }
    }

    /// Asks the server once; on success the account is saved and becomes the one in use.
    func signIn(url: String, username: String, password: String) async throws {
        let account = Account(baseUrl: Self.normalized(url), username: username.trimmingCharacters(in: .whitespaces),
                              password: password)
        let candidate = SubsonicClient(baseUrl: account.baseUrl, username: account.username, password: account.password)
        do {
            let info = try await candidate.ping()
            client?.close()
            client = candidate
            self.account = account
            state = .ready(server: Self.serverName(info))
            Keychain.save(account)
        } catch {
            candidate.close()
            throw error
        }
    }

    func signOut() {
        client?.close()
        client = nil
        account = nil
        state = .signedOut
        Keychain.delete()
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
    }
}
