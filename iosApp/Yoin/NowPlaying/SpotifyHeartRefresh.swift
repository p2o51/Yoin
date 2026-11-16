import Foundation
import UIKit

/// Keeps the heart honest for the Spotify song playing (Android SpotifySavedStateRefresher): asks Liked
/// Songs on every song change, and again on a pause, a return to the foreground or every
/// [recheckSec] while it plays, which catches a like made in the Spotify app, its lock screen or a car.
/// Each answer replaces the last (FavoritesStore.refresh); a read that fails leaves the heart as it was,
/// unknown when nothing was known.
///
/// Owned by the app (PlaybackSystem feeds it the engine's state), so a song is asked about once however
/// many Now Playing hosts are open.
@MainActor
final class SpotifyHeartRefresh {
    static let shared = SpotifyHeartRefresh()

    /// Android FAVORITE_RECHECK_INTERVAL_MS.
    nonisolated static let recheckSec: Double = 30

    /// When to ask: a new song at once, the same song again at most every [recheckSec].
    struct Policy: Equatable {
        var key: String?
        var lastCheckAt = -Double.infinity

        /// [force]: the app came back to the front, where a like made elsewhere shows up at once.
        mutating func shouldCheck(_ key: String?, now: Double, force: Bool = false) -> Bool {
            if key != self.key {
                self.key = key
                guard key != nil else { return false }
                lastCheckAt = now
                return true
            }
            guard key != nil, force || now - lastCheckAt >= SpotifyHeartRefresh.recheckSec else { return false }
            lastCheckAt = now
            return true
        }
    }

    private var policy = Policy()
    private var ticker: Task<Void, Never>?

    /// The engine's song now (a state change, the foreground, the ticker).
    func observe(_ track: PlayableTrack?, force: Bool = false) {
        let spotify = track.flatMap { t in
            t.provider == SpotifyMapping.provider && !SpotifyEpisodes.isEpisode(t) ? t : nil
        }
        guard policy.shouldCheck(spotify.map(NowPlayingData.key), now: ProcessInfo.processInfo.systemUptime, force: force),
              let t = spotify, let source = AppModel.shared.store.spotify else { return }
        let hearts = FavoritesStore.shared
        let token = hearts.readToken(provider: t.provider, songId: t.id)
        Task {
            // savedStates leaves a song out when its read failed: unknown stays unknown.
            guard let saved = await source.savedStates([t.id])[t.id] else { return }
            hearts.refresh(saved, provider: t.provider, songId: t.id, token: token)
        }
    }

    /// Rechecks while the app is in front (the read only goes out for a Spotify song, at most every
    /// [recheckSec]).
    func start(current: @escaping @MainActor () -> PlayableTrack?) {
        guard ticker == nil else { return }
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.recheckSec))
                guard UIApplication.shared.applicationState == .active else { continue }
                self?.observe(current())
            }
        }
    }
}
