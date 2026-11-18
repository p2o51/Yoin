import Foundation
import Observation

/// Which music source the screens read from. One at a time: Android keeps
/// profiles as independent data sources and never mixes them.
enum ActiveSource: String, Sendable {
    /// No source: the mock library the `-screen` screenshots use.
    case mock
    case subsonic
    case appleMusic
    case spotify

    var provider: String? {
        switch self {
        case .mock: nil
        case .subsonic: "subsonic"
        case .appleMusic: AppleMusicMapping.provider
        case .spotify: "spotify"
        }
    }
}

/// The chosen source, kept in UserDefaults (`activeSource`; a launch argument
/// `-activeSource mock` overrides it for screenshots).
@MainActor
@Observable
final class SourceSelection {
    static let key = "activeSource"

    private(set) var active: ActiveSource

    /// Nothing saved yet: the signed-in Subsonic server when there is one
    /// (installs from before this setting), else the mock.
    init(subsonicSignedIn: Bool, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let raw = defaults.string(forKey: Self.key), let saved = ActiveSource(rawValue: raw) {
            active = saved
        } else {
            active = subsonicSignedIn ? .subsonic : .mock
        }
    }

    @ObservationIgnored private let defaults: UserDefaults

    func use(_ source: ActiveSource) {
        guard source != active else { return }
        active = source
        defaults.set(source.rawValue, forKey: Self.key)
    }
}
