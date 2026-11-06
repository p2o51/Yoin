import Foundation
import YoinShared

/// Spotify as a backup source and a restore destination (Android PlaylistBackupService with
/// SpotifyMusicSource): playlists read whole through the Web API, and a restore made as a new private
/// playlist. A Spotify backup restored into Spotify goes back by its own track ids (the same in every
/// account); everything else is matched (ISRC first, then a title search).
extension PlaylistBackups {
    /// [spotify] (one account's source) as a backup source.
    static func spotifySource(_ spotify: SpotifySource, profile: Profile) -> Source {
        Source(provider: SpotifyMapping.provider, profileId: profile.id, label: profile.title,
               listPlaylists: { try await spotify.libraryPlaylists().map { "\($0.id.provider):\($0.id.rawId)" } },
               readPlaylist: { rawId in try await spotify.playlistForBackup(rawId) })
    }
}

/// A Spotify account as a restore target: the catalog searched, a new private playlist in the account.
@MainActor
final class SpotifyRestoreDestination: RestoreDestination {
    let spotify: SpotifySource

    init(spotify: SpotifySource) { self.spotify = spotify }

    var ownProvider: String? { SpotifyMapping.provider }

    func search(_ query: String) async throws -> [MatchTrack] {
        try await spotify.searchSongsForMatch(query)
    }

    func tracksByIsrc(_ isrcs: [String]) async -> [String: [MatchTrack]] {
        await spotify.tracksByIsrc(isrcs)
    }

    func createPlaylist(name: String, description: String?, trackIds: [String]) async throws -> String {
        let made = try await spotify.createPlaylist(name: name, description: description)
        guard !trackIds.isEmpty else { return made.id }
        do {
            try await spotify.addTracks(Self.rawIds(trackIds), to: made.id)
        } catch {
            throw RestoreCreatedEmpty(playlistId: made.id)
        }
        return made.id
    }

    func add(_ trackIds: [String], to playlistId: String) async throws {
        try await spotify.addTracks(Self.rawIds(trackIds), to: playlistId)
    }

    /// `spotify:rawId` ids; another service's id can't go into a Spotify playlist.
    nonisolated static func rawIds(_ ids: [String]) -> [String] {
        ids.filter { PlaylistSnapshots.shared.providerOf(id: $0) == SpotifyMapping.provider }
            .compactMap { PlaylistSnapshots.shared.rawIdOf(id: $0) }
    }
}

#if os(iOS)
extension AppModel {
    /// The Spotify account [profileId] as a source of its own: the library's when it is the account in use,
    /// else one built from its saved tokens (refreshes saved back, a revoke marks it). nil: signed out.
    func spotifySource(forProfile profileId: String) -> SpotifySource? {
        if profiles.activeId == profileId, let source = store.spotifyAccount?.source { return source }
        guard let p = profiles.profile(profileId), p.service == .spotify, !profiles.needsSignIn(p),
              let tokens = profiles.store.spotify(profileId) else { return nil }
        let profiles = profiles
        let provider = SpotifyTokenProvider(profileId: profileId, tokens: tokens, onRefreshed: { fresh in
            Task { @MainActor in profiles.store.updateSpotify(fresh, for: profileId) }
        }) {
            Task { @MainActor in profiles.markSpotifyRevoked(profileId) }
        }
        let api = SpotifyWebAPI(token: { try await provider.accessToken() },
                                refresh: { _ = try await provider.accessToken(force: true) })
        let source = SpotifySource(api: api)
        source.userId = tokens.userId
        return source
    }
}
#endif
