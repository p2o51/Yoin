import Foundation
import Observation
import SwiftUI
import YoinShared
import os

/// The one heart per song: Now Playing, the song sheet and the album's
/// track rows all read and write it here, so a heart set in one shows in
/// the others at once. Android keeps it in one place too (the repository's
/// star writes, observed through `favoriteFlow`).
///
/// Optimistic: the heart flips at once and flips back when the write fails
/// (only if no newer tap owns it by then). On Apple Music the heart is
/// library membership: add only, since MusicKit has no remove.
@MainActor
@Observable
final class FavoritesStore {
    static let shared = FavoritesStore()

    /// Hearts as the user last set them here (ahead of the server).
    private(set) var chosen: [String: Bool] = [:]
    /// What the source said, where the caller had nothing fresher
    /// (Now Playing's album / getSong / library lookups).
    private(set) var reported: [String: Bool] = [:]

    /// A song's heart was written (LibraryStore drops the pages that show it).
    @ObservationIgnored var onWrite: ((_ provider: String, _ songId: String) -> Void)?
    @ObservationIgnored private let log = Logger(subsystem: "com.gpo.yoin", category: "favorites")

    static func key(provider: String, songId: String) -> String { LocalRatings.key(provider: provider, songId: songId) }

    /// The user's own tap, else [known] (what the caller's copy of the song
    /// says), else what the source reported, else off.
    func isFavorite(provider: String, songId: String, known: Bool? = nil) -> Bool {
        let k = Self.key(provider: provider, songId: songId)
        return chosen[k] ?? known ?? reported[k] ?? false
    }

    /// Whether anything is known about the song beyond the caller's copy.
    func hasReport(provider: String, songId: String) -> Bool {
        reported[Self.key(provider: provider, songId: songId)] != nil
    }

    /// The source's answer; the first one stands unless [overwrite].
    func report(_ starred: Bool, provider: String, songId: String, overwrite: Bool = false) {
        let k = Self.key(provider: provider, songId: songId)
        if overwrite || reported[k] == nil { reported[k] = starred }
    }

    /// Bumps on every reset (sign-in / sign-out / another source): a write that answers after it touches
    /// nothing (Codex C10).
    @ObservationIgnored private var generation = 0

    /// Flips the heart. Subsonic stars / unstars; Apple Music only adds to
    /// the library (an added song stays added). Without a client (mock) the
    /// heart is local only.
    func toggle(provider: String, songId: String, known: Bool? = nil,
                client: SubsonicClient?, apple: AppleMusicSource?) {
        let was = isFavorite(provider: provider, songId: songId, known: known)
        if provider == AppleMusicMapping.provider {
            guard !was, let apple else { return }
            write(provider: provider, songId: songId, target: true, was: was) {
                try await apple.addToLibrary(songId)
            }
            return
        }
        if provider == SpotifyMapping.provider, let spotify = AppModel.shared.store.spotify {
            // Liked Songs: Spotify saves and un-saves (Android SpotifyMusicSource.setFavorite).
            let target = !was
            write(provider: provider, songId: songId, target: target, was: was) {
                if target { try await spotify.save(songId) } else { try await spotify.unsave(songId) }
            }
            return
        }
        guard let client, provider == MediaRef.companion.PROVIDER_SUBSONIC else {
            write(provider: provider, songId: songId, target: !was, was: was, send: nil)
            return
        }
        let target = !was
        write(provider: provider, songId: songId, target: target, was: was) {
            if target {
                try await client.star(id: songId, albumId: nil, artistId: nil)
            } else {
                try await client.unstar(id: songId, albumId: nil, artistId: nil)
            }
        }
    }

    /// The heart flips at once; [send] tells the source. Writes for one song
    /// go out one at a time, newest intent last (Android serializes them with
    /// a mutex): a quick on→off can't land out of order, and a failure rolls
    /// the heart back to what the source last confirmed, never to a stale
    /// `was` that no write ever reached (Codex review #18). All only for the
    /// same account generation.
    func write(provider: String, songId: String, target: Bool, was: Bool,
               send: (@MainActor () async throws -> Void)?) {
        let k = Self.key(provider: provider, songId: songId)
        let gen = generation
        if confirmed[k] == nil { confirmed[k] = was }
        chosen[k] = target
        onWrite?(provider, songId)
        guard let send else { return }
        pending[k] = (target, send)
        guard sending.insert(k).inserted else { return }
        Task {
            defer { if gen == generation { sending.remove(k) } }
            while gen == generation, let next = pending.removeValue(forKey: k) {
                let (want, push) = next
                // Already what the source holds (on→off→on before the first went out).
                if want == confirmed[k] { continue }
                do {
                    try await push()
                    guard gen == generation else { return }
                    confirmed[k] = want
                    reported[k] = want
                } catch {
                    log.error("heart write failed: \(ServerSession.message(for: error), privacy: .public)")
                    guard gen == generation else { return }
                    // A newer tap is queued: it decides. Otherwise back to the source's value.
                    if pending[k] == nil, let c = confirmed[k] {
                        withAnimation(Yoin.effects) { chosen[k] = c }
                    }
                }
            }
        }
    }

    /// Per song, the value the source last confirmed (or held before the first tap).
    @ObservationIgnored private var confirmed: [String: Bool] = [:]
    /// Per song, the newest write not yet sent.
    @ObservationIgnored private var pending: [String: (Bool, @MainActor () async throws -> Void)] = [:]
    /// Songs with a write loop running.
    @ObservationIgnored private var sending: Set<String> = []

    /// Sign-in / sign-out / another source: the user's taps and the source's answers belong to the old one.
    func reset() {
        generation += 1
        chosen = [:]
        reported = [:]
        confirmed = [:]
        pending = [:]
        sending = []
    }
}
