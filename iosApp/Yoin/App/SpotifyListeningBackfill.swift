import Foundation
import UIKit

/// The songs Spotify played while Yoin was away from the front (audit 11-15 #18): App Remote is closed in
/// the background, so the engine sees none of them, and a lock-screen hour of an album never reached
/// history, Memories, play counts, Home or the Play widget. When Yoin left the front with a Spotify song
/// playing, coming back reads me/player/recently-played after that moment and records each song it lists
/// that history doesn't already hold, at the time Spotify played it (Android records every song Spotify
/// plays, PlaybackManager.recordPlayIfDue). Spotify lists a song there once it was heard for 30 s or
/// more, so each one counts as a play.
@MainActor
final class SpotifyListeningBackfill {
    static let shared = SpotifyListeningBackfill()

    /// One song recently-played lists.
    struct Heard: Equatable, Sendable {
        var songId: String
        /// Epoch ms Spotify gives as played_at.
        var playedAt: Int64
        var durationSec: Double
        var title: String
        var album: String
        var albumId: String?
        var artist: String
        var artistId: String?
        var coverURL: URL?
    }

    /// Minimum delay between recently-played backfill checks per scope (skips App Remote wake bounces).
    nonisolated static let minGapMs: Int64 = 15_000

    /// When Yoin left the front with a real Spotify account active, keyed by historyScope.
    private var awayAtByScope: [String: Int64] = [:]
    private var lastAttemptByScope: [String: Int64] = [:]
    private var inFlightScopes: Set<String> = []
    private var observers: [NSObjectProtocol] = []

    func start(model: AppModel) {
        guard observers.isEmpty else { return }
        let c = NotificationCenter.default
        observers.append(c.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self, weak model] _ in
            MainActor.assumeIsolated { if let model { self?.leftFront(model) } }
        })
        observers.append(c.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self, weak model] _ in
            MainActor.assumeIsolated { if let model { self?.backInFront(model) } }
        })
    }

    private func leftFront(_ model: AppModel) {
        let store = model.store
        guard !LaunchConfig.silentAudio, store.spotify != nil, store.isReal,
              store.provider == SpotifyMapping.provider else { return }
        let scope = store.historyScope
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        if awayAtByScope[scope] == nil {
            awayAtByScope[scope] = nowMs
        }
        if Self.loadLastBackfillMs(scope: scope) == nil {
            Self.saveLastBackfillMs(nowMs, scope: scope)
        }
    }

    private func backInFront(_ model: AppModel) {
        let store = model.store
        guard !LaunchConfig.silentAudio, let source = store.spotify, store.isReal,
              store.provider == SpotifyMapping.provider else { return }
        let scope = store.historyScope
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let awayAt = awayAtByScope.removeValue(forKey: scope)
        let persisted = Self.loadLastBackfillMs(scope: scope)
        if persisted == nil {
            Self.saveLastBackfillMs(nowMs, scope: scope)
        }
        guard !inFlightScopes.contains(scope),
              let since = Self.resolveSinceMs(nowMs: nowMs, awayAt: awayAt,
                                              lastAttemptMs: lastAttemptByScope[scope],
                                              persistedMs: persisted) else { return }
        lastAttemptByScope[scope] = nowMs
        inFlightScopes.insert(scope)
        Task {
            defer { self.inFlightScopes.remove(scope) }
            guard await source.api.gate.remaining() == 0 else { return }
            let page: SpotifyDTO.RecentlyPlayed
            do {
                page = try await source.api.get("me/player/recently-played", ["limit": "50", "after": String(since)],
                                                as: SpotifyDTO.RecentlyPlayed.self)
            } catch {
                PlayTrace.log("SP backfill read failed \(SpotifySource.message(for: error))")
                return
            }
            // Another account (or none) by the time it answered: not its history.
            guard store.spotify === source, store.historyScope == scope else { return }
            let provider = SpotifyMapping.provider
            let heard = Self.heard(page.items)
            let existing = store.history.plays.filter { $0.scope == scope && $0.provider == provider }
                .map { (songId: $0.songId, startedAt: $0.startedAt) }
            let missing = Self.missing(heard, after: since, existing: existing)
            for h in missing {
                let id = store.history.recordPlay(scope: scope, provider: provider, songId: h.songId, albumId: h.albumId,
                                                  artistId: h.artistId, title: h.title, album: h.album, artist: h.artist,
                                                  coverKey: LibraryStore.coverKey(h.coverURL, provider: provider),
                                                  durationSec: h.durationSec, at: h.playedAt)
                store.history.markListened(playId: id, listenedSec: 0, completed: true)
            }
            let watermark = max(persisted ?? 0, heard.last?.playedAt ?? since)
            Self.saveLastBackfillMs(watermark, scope: scope)
            if !missing.isEmpty { PlayTrace.log("SP backfill recorded \(missing.count)") }
        }
    }

    nonisolated static func watermarkKey(scope: String) -> String {
        "spotifyBackfillLastMs.\(scope)"
    }

    nonisolated static func loadLastBackfillMs(scope: String, defaults: UserDefaults = .standard) -> Int64? {
        guard !scope.isEmpty, let raw = defaults.object(forKey: watermarkKey(scope: scope)) as? NSNumber else { return nil }
        let val = raw.int64Value
        return val > 0 ? val : nil
    }

    nonisolated static func saveLastBackfillMs(_ ms: Int64, scope: String, defaults: UserDefaults = .standard) {
        guard !scope.isEmpty, ms > 0 else { return }
        defaults.set(NSNumber(value: ms), forKey: watermarkKey(scope: scope))
    }

    nonisolated static func resolveSinceMs(nowMs: Int64, awayAt: Int64?, lastAttemptMs: Int64?,
                                           persistedMs: Int64?, minGapMs: Int64 = minGapMs) -> Int64? {
        if let lastAttemptMs, nowMs - lastAttemptMs < minGapMs { return nil }
        if let awayAt, let persistedMs {
            return min(awayAt, persistedMs)
        }
        if let awayAt {
            return (nowMs - awayAt >= minGapMs) ? awayAt : nil
        }
        return persistedMs
    }

    /// recently-played's songs (an episode or a local file is no song), oldest first.
    nonisolated static func heard(_ items: [SpotifyDTO.PlayHistory]) -> [Heard] {
        items.compactMap { item -> Heard? in
            guard let t = item.track, let id = t.id, !id.isEmpty, t.isLocal != true, t.type == nil || t.type == "track",
                  let at = item.playedAt.flatMap(playedAtMs) else { return nil }
            let images = t.album?.images ?? []
            let cover = images.max { ($0.width ?? 0) < ($1.width ?? 0) }.flatMap { URL(string: $0.url) }
            return Heard(songId: id, playedAt: at, durationSec: Double(t.durationMs ?? 0) / 1000, title: t.name ?? "",
                         album: t.album?.name ?? "", albumId: t.album?.id.flatMap { $0.isEmpty ? nil : $0 },
                         // The first artist, as App Remote names a song's artist.
                         artist: t.artists?.first?.name ?? "", artistId: t.artists?.first?.id, coverURL: cover)
        }
        .sorted { $0.playedAt < $1.playedAt }
    }

    /// The songs played after [after] that history doesn't hold: a play of the same song already recorded
    /// within the song's length (and two minutes' slack) of Spotify's time is the same play, recorded live.
    nonisolated static func missing(_ heard: [Heard], after: Int64,
                                    existing: [(songId: String, startedAt: Int64)]) -> [Heard] {
        heard.filter { h in
            guard h.playedAt > after else { return false }
            let slack = Int64(h.durationSec * 1000) + 120_000
            return !existing.contains { $0.songId == h.songId && abs($0.startedAt - h.playedAt) <= slack }
        }
    }

    /// "2026-11-15T12:34:56.789Z" (fractional seconds or not) → epoch ms.
    nonisolated static func playedAtMs(_ s: String) -> Int64? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return Int64((d.timeIntervalSince1970 * 1000).rounded()) }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s).map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
    }
}
