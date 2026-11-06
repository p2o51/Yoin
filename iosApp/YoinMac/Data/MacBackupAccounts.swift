import Foundation
import MusicKit
import YoinShared

// The Mac's side of the iOS playlist backups (Data/Backup): its accounts in the shapes those files name.

/// iOS's `Profile` is the Mac session's profile here (same id / service / title).
typealias Profile = MacSession.Profile

extension MacSession.Profile {
    var baseUrl: String? { account?.baseUrl }
    var username: String? { account?.username }
    var subsonicScope: String? { account.map { "\($0.baseUrl)|\($0.username)" } }
}

/// What the iOS files read from `ServerSession` (that file isn't on the Mac).
@MainActor
enum ServerSession {
    typealias Account = MacSession.Account
    static func message(for error: Error) -> String { MacSession.message(for: error) }
}

/// iOS `ProfileStore.appleMusicId` (a default the Mac always overrides with its own profile id).
enum ProfileStore {
    static let appleMusicId = "ios-applemusic"
}

/// The Mac's accounts for backups and restores (iOS LiveBackupAccounts): every profile here, each opened
/// on its own, so following and restoring never depend on — or change — the account in use.
@MainActor
final class MacBackupAccounts: BackupAccounts {
    static let shared = MacBackupAccounts()
    /// Set at launch (MacModel).
    weak var session: MacSession?

    var profiles: [Profile] { session?.profiles ?? [] }
    var activeId: String? { session?.activeId }

    func canRead(_ profileId: String) -> Bool {
        guard let p = profiles.first(where: { $0.id == profileId }) else { return false }
        switch p.service {
        case .subsonic: return p.account != nil
        case .appleMusic: return MusicAuthorization.currentStatus == .authorized
        case .spotify: return SpotifyTokenStore.load(p.id) != nil
        }
    }

    func source(_ profileId: String) -> PlaylistBackups.Source? {
        guard let p = profiles.first(where: { $0.id == profileId }), canRead(profileId) else { return nil }
        switch p.service {
        case .subsonic: return p.account.map { PlaylistBackups.subsonicSource(p, account: $0) }
        case .appleMusic: return PlaylistBackups.appleSource(apple, profileId: p.id)
        case .spotify: return spotify(p.id).map { PlaylistBackups.spotifySource($0, profile: p) }
        }
    }

    func destination(_ profileId: String) -> RestoreDestination? {
        guard let p = profiles.first(where: { $0.id == profileId }), canRead(profileId) else { return nil }
        switch p.service {
        case .subsonic: return p.account.map { SubsonicRestoreDestination(account: $0) }
        case .appleMusic: return AppleMusicRestoreDestination(apple: apple)
        case .spotify: return spotify(p.id).map(SpotifyRestoreDestination.init(spotify:))
        }
    }

    /// The Apple Music in use, else one of its own (the system's access is per app, not per account).
    private var apple: AppleMusicSource { session?.apple ?? AppleMusicSource() }

    /// The Spotify account [profileId] on its own Web API (the one in use when it is that account).
    private func spotify(_ profileId: String) -> SpotifySource? {
        if session?.activeId == profileId, let s = session?.spotify { return s }
        guard let tokens = SpotifyTokenStore.load(profileId) else { return nil }
        return MacSession.spotifySource(profileId: profileId, tokens: tokens, onRevoked: {})
    }
}
