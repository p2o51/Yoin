import Observation
@preconcurrency import SpotifyiOS
import UIKit

/// The Spotify app on this device (Android CouldNotFindSpotifyApp).
@MainActor
enum SpotifyApp {
    /// Whether it is installed (`spotify` is in LSApplicationQueriesSchemes).
    static var isInstalled: Bool {
        URL(string: "spotify:").map { UIApplication.shared.canOpenURL($0) } ?? false
    }

    /// Its App Store page, by the id the Spotify SDK gives for installing it.
    static var storeURL: URL? { storeURL(itemId: SPTAppRemote.spotifyItunesItemIdentifier().intValue) }

    nonisolated static func storeURL(itemId: Int) -> URL? {
        itemId > 0 ? URL(string: "https://apps.apple.com/app/id\(itemId)") : nil
    }

    static func openStore() {
        guard let url = storeURL else { return }
        // A simulator has no App Store: nothing opens there (the log says so).
        UIApplication.shared.open(url) { opened in PlayTrace.log("SP store page \(url.absoluteString) opened=\(opened)") }
    }
}

/// What keeps Spotify from playing, kept for this run and said on the account's card and page
/// (Android SpotifyProviderStatus.SpotifyAppMissing / NoPremium over AppContainer's sticky failure).
/// The missing app is this device's; a Premium refusal is the account's.
@MainActor
@Observable
final class SpotifyIssues {
    static let shared = SpotifyIssues()

    enum Issue: Equatable, Sendable {
        case appMissing, premiumRequired

        /// The card's pill (settings_spotify_install, settings_spotify_premium_required).
        var label: String {
            switch self {
            case .appMissing: String(localized: "Install Spotify")
            case .premiumRequired: String(localized: "Premium required")
            }
        }

        /// The banner's line (player_spotify_app_missing, player_spotify_premium_required).
        var message: String {
            switch self {
            case .appMissing: SpotifyFailureCopy.appMissing
            case .premiumRequired: SpotifyFailureCopy.premium
            }
        }

        /// The issue a failure line stands for; nil for every other failure.
        init?(message: String) {
            if message == SpotifyFailureCopy.appMissing {
                self = .appMissing
            } else if message == SpotifyFailureCopy.premium {
                self = .premiumRequired
            } else {
                return nil
            }
        }
    }

    /// A start on this device found no Spotify app.
    private(set) var appMissing = false
    /// The accounts (profile ids) Spotify refused to play for without Premium.
    private(set) var premiumRequired: Set<String> = []

    /// What [profileId]'s card says: the missing app first (Android toProfileCard's order).
    func issue(for profileId: String) -> Issue? {
        if appMissing { return .appMissing }
        return premiumRequired.contains(profileId) ? .premiumRequired : nil
    }

    func note(_ issue: Issue, account: String?) {
        switch issue {
        case .appMissing: if !appMissing { appMissing = true }
        case .premiumRequired: if let account { premiumRequired.insert(account) }
        }
    }

    /// The Spotify app is on this device after all (it connected, or was installed since).
    func appFound() {
        if appMissing { appMissing = false }
    }

    /// Spotify took a start for [account]: it may play.
    func started(account: String?) {
        if let account, premiumRequired.contains(account) { premiumRequired.remove(account) }
    }

    /// [account] signed in again or was removed: what Spotify said of it before no longer counts.
    func forget(_ account: String) {
        if premiumRequired.contains(account) { premiumRequired.remove(account) }
    }
}
