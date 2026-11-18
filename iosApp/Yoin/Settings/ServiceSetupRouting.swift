import Foundation

/// The small decisions Settings makes about service setup, kept pure so they can be tested.
enum ServiceSetupRouting {
    /// The service setup an Add on a cloud-only account opens (Android SetupService.key);
    /// nil for a provider iOS has no setup page for (no Add).
    static func service(forProvider provider: String) -> Profile.Service? {
        switch provider {
        case "subsonic": .subsonic
        case "spotify": .spotify
        case "applemusic": .appleMusic
        default: nil
        }
    }

    /// Spotify's sign-in button: always on an existing account's page (Android ServiceSetupScreen),
    /// and while adding.
    static func showsSpotifySignIn(adding: Bool, hasAccount: Bool) -> Bool { adding || hasAccount }
}
