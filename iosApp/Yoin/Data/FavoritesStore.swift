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

    /// Flips the heart. Subsonic stars / unstars; Apple Music only adds to
    /// the library (an added song stays added). Without a client (mock) the
    /// heart is local only.
    func toggle(provider: String, songId: String, known: Bool? = nil,
                client: SubsonicClient?, apple: AppleMusicSource?) {
        let k = Self.key(provider: provider, songId: songId)
        let was = isFavorite(provider: provider, songId: songId, known: known)
        let target = !was
        if provider == AppleMusicMapping.provider {
            guard !was, let apple else { return }
            chosen[k] = true
            onWrite?(provider, songId)
            Task {
                do {
                    try await apple.addToLibrary(songId)
                    reported[k] = true
                } catch {
                    log.error("add to library failed")
                    if chosen[k] == true { withAnimation(Yoin.effects) { chosen[k] = false } }
                }
            }
            return
        }
        chosen[k] = target
        onWrite?(provider, songId)
        guard let client, provider == MediaRef.companion.PROVIDER_SUBSONIC else { return }
        Task {
            do {
                if target {
                    try await client.star(id: songId, albumId: nil, artistId: nil)
                } else {
                    try await client.unstar(id: songId, albumId: nil, artistId: nil)
                }
                reported[k] = target
            } catch {
                log.error("star failed: \(ServerSession.message(for: error), privacy: .public)")
                // Only undo our own write: a newer tap owns the heart now.
                if chosen[k] == target { withAnimation(Yoin.effects) { chosen[k] = was } }
            }
        }
    }

    /// Sign-in / sign-out / another source: the user's taps belong to the old one.
    func reset() {
        chosen = [:]
    }
}
