import Foundation

/// Simulator QA without a Spotify account (DEBUG only): `-spotifyFake http://127.0.0.1:4997` points the
/// Web API at `iosApp/scripts/fake_spotify.py` and signs in a stand-in account (`yoin-qa`) with dummy
/// tokens that never expire, so no refresh and no consent page. App Remote is never asked: the
/// Spotify app is left alone and a play start goes nowhere (launch with `-silentAudio YES` as always);
/// with no Spotify app on the device, as on a simulator, it is held with Install Spotify.
enum SpotifyFake {
    /// The fake server's base URL; nil in a release build or without the launch argument.
    static var base: URL? {
        #if DEBUG
        guard let s = UserDefaults.standard.string(forKey: "spotifyFake"), !s.isEmpty else { return nil }
        return URL(string: s.hasSuffix("/") ? String(s.dropLast()) : s)
        #else
        return nil
        #endif
    }

    static var isOn: Bool { base != nil }

    /// `-spotifyFakePlayer YES` beside `-spotifyFake`: the Spotify output runs its starts, reads and
    /// commands against the fake's Connect player even in a `-silentAudio YES` launch. The fake makes
    /// no sound and App Remote stays off, so nothing can be heard.
    static var drivesPlayer: Bool { isOn && UserDefaults.standard.bool(forKey: "spotifyFakePlayer") }

    static let userId = "yoin-qa"

    /// Tokens for the stand-in account: every required scope, an expiry far away.
    static var tokens: SpotifyTokens {
        SpotifyTokens(accessToken: "fake-access", refreshToken: "fake-refresh",
                      expiresAt: Date.distantFuture.timeIntervalSince1970,
                      scope: SpotifyConfig.requiredScopes.sorted().joined(separator: " ")
                          + " user-library-read user-library-modify user-follow-read playlist-read-private",
                      clientId: "fake-client", userId: userId, displayName: "Yoin QA")
    }
}
