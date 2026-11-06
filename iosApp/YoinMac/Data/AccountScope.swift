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

    /// The Spotify account in use's scope ("spotify|<user id>", as on the iOS branch); nil without one.
    var spotify: String?

    nonisolated static let spotifyProvider = "spotify"
    nonisolated static func spotifyScope(userId: String) -> String { "spotify|\(userId)" }

    /// The scope a row of [provider] is written under and read back in.
    func scope(for provider: String) -> String? {
        if provider == Self.subsonicProvider { return pinned ?? subsonic }
        if provider == Self.spotifyProvider { return pinnedSpotify ?? spotify }
        return nil
    }

    /// Set only inside `reading`: the account the stores read and write for the moment.
    @ObservationIgnored private var pinned: String?
    @ObservationIgnored private var pinnedSpotify: String?

    /// Runs [body] as if [subsonicScope] / [spotifyScope] were the account in use, without switching.
    func reading<T>(asSubsonic subsonicScope: String?, asSpotify spotifyScope: String? = nil, _ body: () -> T) -> T {
        let before = (pinned, pinnedSpotify)
        if let subsonicScope { pinned = subsonicScope }
        if let spotifyScope { pinnedSpotify = spotifyScope }
        defer { (pinned, pinnedSpotify) = before }
        return body()
    }

    func isVisible(provider: String, scope: String?) -> Bool { scope == self.scope(for: provider) }

    static let subsonicProvider = "subsonic"
}
