import Foundation
import Observation

/// The Mac's copy of iOS `AccountScope` (ServerSession.swift isn't on the Mac): which account local rows
/// (notes, ratings, album memories) belong to. A Subsonic row carries its server + user
/// ("baseUrl|username"); other providers have one account per device here and no scope. Cloud sync reads
/// another account's rows through `reading`.
@MainActor
@Observable
final class AccountScope {
    static let shared = AccountScope()

    /// The signed-in Subsonic account's scope; nil when Subsonic isn't in use.
    var subsonic: String?

    /// The scope a row of [provider] is written under and read back in.
    func scope(for provider: String) -> String? { provider == Self.subsonicProvider ? (pinned ?? subsonic) : nil }

    /// Set only inside `reading`: the Subsonic account the stores read and write for the moment.
    @ObservationIgnored private var pinned: String?

    /// Runs [body] as if [subsonicScope] were the account in use, without switching.
    func reading<T>(asSubsonic subsonicScope: String?, _ body: () -> T) -> T {
        guard let subsonicScope else { return body() }
        let before = pinned
        pinned = subsonicScope
        defer { pinned = before }
        return body()
    }

    func isVisible(provider: String, scope: String?) -> Bool { scope == self.scope(for: provider) }

    static let subsonicProvider = "subsonic"
}
